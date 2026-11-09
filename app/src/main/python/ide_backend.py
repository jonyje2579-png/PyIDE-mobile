import ast
import bdb
import code
import ctypes
import difflib
import importlib
import io
import json
import os
import queue
import re
import sys
import threading
import time
import traceback
import types
import urllib.request
import zipfile

ROOT = ""
SITE = ""


def init(root, site):
    global ROOT, SITE
    ROOT, SITE = root, site
    os.makedirs(site, exist_ok=True)
    for p in (root, site):
        if p not in sys.path:
            sys.path.insert(0, p)
    os.chdir(root)
    repl_reset()


# ====================== Запуск / отладка ======================

class Out:
    encoding = "utf-8"
    errors = "replace"

    def __init__(self, s):
        self.s = s

    def write(self, t):
        if t:
            self.s._emit(t)
        return len(t)

    def flush(self):
        pass

    def isatty(self):
        return False


class In:
    encoding = "utf-8"

    def __init__(self, s):
        self.s = s

    def readline(self, size=-1):
        return self.s._wait_input()

    def read(self, size=-1):
        return ""

    def isatty(self):
        return False

    def __iter__(self):
        return self

    def __next__(self):
        line = self.readline()
        if not line:
            raise StopIteration
        return line


class Dbg(bdb.Bdb):
    def __init__(self, s):
        super().__init__()
        self.s = s
        self.cmds = queue.Queue()
        self.first = True

    def _mine(self, frame):
        fn = frame.f_code.co_filename
        return bool(ROOT) and fn.startswith(ROOT)

    def stop_here(self, frame):
        if not self._mine(frame):
            return False
        return super().stop_here(frame)

    def user_line(self, frame):
        if self.first:
            self.first = False
            if not self.break_here(frame):
                self.set_continue()
                return
        self.s._pause(self, frame)


class Session:
    def __init__(self):
        self.lock = threading.Lock()
        self.buf = []
        self.thread = None
        self.running = False
        self.waiting_input = False
        self.stop_flag = False
        self.inq = queue.Queue()
        self.dbg = None
        self.pause = None
        self.pause_frame = None
        self.pause_id = 0
        self.breaks = {}

    # ---- вывод / ввод ----
    def _emit(self, t):
        with self.lock:
            self.buf.append(t)

    def drain(self):
        with self.lock:
            s = "".join(self.buf)
            self.buf.clear()
            return s

    def _wait_input(self):
        self.waiting_input = True
        try:
            while True:
                try:
                    line = self.inq.get(timeout=0.2)
                except queue.Empty:
                    if self.stop_flag:
                        return ""
                    continue
                self._emit(line + "\n")
                return line + "\n"
        finally:
            self.waiting_input = False

    # ---- запуск ----
    def start(self, path, debug):
        if self.running:
            return
        import linecache
        linecache.checkcache()
        self.stop_flag = False
        self.pause = None
        while not self.inq.empty():
            self.inq.get_nowait()
        self.running = True
        self.thread = threading.Thread(target=self._main, args=(path, debug), daemon=True)
        self.thread.start()

    def _main(self, path, debug):
        old_io = (sys.stdout, sys.stderr, sys.stdin)
        old_main = sys.modules.get("__main__")
        old_cwd = os.getcwd()
        old_argv = sys.argv
        folder = os.path.dirname(path)
        t0 = time.time()
        sys.stdout = sys.stderr = Out(self)
        sys.stdin = In(self)
        sys.argv = [path]
        os.chdir(folder)
        if folder not in sys.path:
            sys.path.insert(0, folder)
        try:
            with open(path, encoding="utf-8") as f:
                src = f.read()
            mod = types.ModuleType("__main__")
            mod.__file__ = path
            sys.modules["__main__"] = mod
            codeobj = compile(src, path, "exec")
            if debug:
                self.dbg = Dbg(self)
                for p, ls in self.breaks.items():
                    for l in ls:
                        self.dbg.set_break(p, l)
                self.dbg.run(codeobj, mod.__dict__)
            else:
                exec(codeobj, mod.__dict__)
        except SystemExit:
            pass
        except KeyboardInterrupt:
            self._emit("\n[остановлено]\n")
        except BaseException:
            etype, e, tb = sys.exc_info()
            while tb is not None and tb.tb_frame.f_code.co_filename != path:
                tb = tb.tb_next
            if tb is None:
                self._emit("".join(traceback.format_exception_only(etype, e)))
            else:
                self._emit("".join(traceback.format_exception(etype, e, tb)))
        finally:
            sys.stdout, sys.stderr, sys.stdin = old_io
            sys.argv = old_argv
            if old_main is not None:
                sys.modules["__main__"] = old_main
            try:
                os.chdir(old_cwd)
            except Exception:
                pass
            for name, m in list(sys.modules.items()):
                fn = getattr(m, "__file__", None)
                if fn and fn.startswith(ROOT) and name != "__main__":
                    sys.modules.pop(name, None)
            self._emit("\n[завершено за %.1f c]\n" % (time.time() - t0))
            with self.lock:
                self.running = False
                self.pause = None
            self.dbg = None
            self.pause_frame = None

    # ---- отладчик ----
    def _pause(self, dbg, frame):
        stack = []
        f = frame
        while f is not None:
            fn = f.f_code.co_filename
            if fn.startswith(ROOT):
                stack.append({"file": fn, "line": f.f_lineno, "fn": f.f_code.co_name})
            f = f.f_back
        skip = (types.ModuleType, types.FunctionType, types.BuiltinFunctionType, type)
        vs = []
        for k, v in list(frame.f_locals.items()):
            if k.startswith("__") or isinstance(v, skip):
                continue
            try:
                r = repr(v)
            except Exception:
                r = "<?>"
            vs.append([k, type(v).__name__, r[:200]])
        self.pause_id += 1
        info = {"id": self.pause_id, "file": frame.f_code.co_filename,
                "line": frame.f_lineno, "stack": stack, "vars": vs}
        self.pause_frame = frame
        with self.lock:
            self.pause = info
        cmd = dbg.cmds.get()
        with self.lock:
            self.pause = None
        self.pause_frame = None
        if cmd == "c":
            dbg.set_continue()
        elif cmd == "n":
            dbg.set_next(frame)
        elif cmd == "s":
            dbg.set_step()
        elif cmd == "r":
            dbg.set_return(frame)
        else:
            dbg.set_quit()

    def dbg_cmd(self, c):
        d = self.dbg
        if d is not None and self.pause is not None:
            d.cmds.put(c)

    def eval_expr(self, expr):
        fr = self.pause_frame
        if fr is None:
            return "программа не на паузе"
        try:
            return repr(eval(expr, fr.f_globals, fr.f_locals))[:500]
        except BaseException as e:
            return "%s: %s" % (type(e).__name__, e)

    def set_breaks(self, path, csv):
        lines = {int(x) for x in csv.split(",") if x.strip()}
        self.breaks[path] = lines
        d = self.dbg
        if d is not None:
            try:
                d.clear_all_file_breaks(path)
                for l in lines:
                    d.set_break(path, l)
            except Exception:
                pass

    # ---- стоп / состояние ----
    def stop(self):
        if not self.running:
            return
        self.stop_flag = True
        paused = self.pause is not None
        if self.dbg is not None:
            self.dbg.cmds.put("q")
        t = self.thread
        if t is not None and t.ident and not paused:
            ctypes.pythonapi.PyThreadState_SetAsyncExc(
                ctypes.c_ulong(t.ident), ctypes.py_object(KeyboardInterrupt))

    def state(self):
        with self.lock:
            return json.dumps({"running": self.running,
                               "input": self.waiting_input,
                               "pause": self.pause})


S = Session()


def start(path, debug):
    S.start(path, bool(debug))


def drain():
    return S.drain()


def state():
    return S.state()


def send_input(line):
    S.inq.put(line)


def stop():
    S.stop()


def dbg_cmd(c):
    S.dbg_cmd(c)


def dbg_eval(e):
    return S.eval_expr(e)


def set_breaks(path, csv):
    S.set_breaks(path, csv)


# ====================== Терминал (REPL) ======================

_repl = None


def repl_reset():
    global _repl
    _repl = code.InteractiveConsole({"__name__": "__console__"})


def repl_push(line):
    if S.running:
        return json.dumps({"out": "Сначала остановите запущенную программу\n", "more": False})
    out = io.StringIO()
    old = (sys.stdout, sys.stderr)
    sys.stdout = sys.stderr = out
    try:
        more = _repl.push(line)
    except BaseException:
        out.write(traceback.format_exc())
        more = False
    finally:
        sys.stdout, sys.stderr = old
    return json.dumps({"out": out.getvalue(), "more": bool(more)})


# ====================== Умный редактор ======================

def lint(src, path):
    try:
        tree = ast.parse(src, path)
    except SyntaxError as e:
        return json.dumps([[e.lineno or 1, max((e.offset or 1) - 1, 0), "SyntaxError: " + str(e.msg)]])
    res = []
    try:
        from pyflakes import checker
        w = checker.Checker(tree, path)
        for m in sorted(w.messages, key=lambda m: (m.lineno, m.col)):
            res.append([m.lineno, m.col, m.message % m.message_args])
    except Exception:
        pass
    return json.dumps(res)


_KW = ("False None True and as assert async await break class continue def del elif else "
       "except finally for from global if import in is lambda nonlocal not or pass raise "
       "return try while with yield print len range str int float list dict set tuple open "
       "input enumerate zip map filter sorted sum min max abs isinstance").split()


def _fallback(src, line, col):
    lines = src.split("\n")
    cur = lines[line - 1][:col] if 0 < line <= len(lines) else ""
    m = re.search(r"([A-Za-z_]\w*)$", cur)
    if not m:
        return []
    pref = m.group(1)
    words = set(_KW) | set(re.findall(r"[A-Za-z_]\w*", src))
    return [[w, w[len(pref):], "word"] for w in sorted(words)
            if w.startswith(pref) and w != pref][:30]


def complete(src, line, col, path):
    try:
        import jedi
        from jedi.api.environment import InterpreterEnvironment
        s = jedi.Script(src, path=path, environment=InterpreterEnvironment(),
                        project=jedi.Project(ROOT))
        out = [[c.name, c.complete or "", c.type] for c in s.complete(line, col)[:40]]
        if out:
            return json.dumps(out)
    except Exception:
        pass
    return json.dumps(_fallback(src, line, col))


def goto(src, line, col, path):
    try:
        import jedi
        from jedi.api.environment import InterpreterEnvironment
        s = jedi.Script(src, path=path, environment=InterpreterEnvironment(),
                        project=jedi.Project(ROOT))
        out = []
        for d in s.goto(line, col, follow_imports=True):
            if d.module_path and d.line:
                out.append([str(d.module_path), d.line, d.column or 0])
        return json.dumps(out)
    except Exception:
        return "[]"


def format_code(src):
    try:
        import autopep8
        return autopep8.fix_code(src)
    except Exception:
        return src


# ====================== pip во время работы ======================
# 1) чисто-Python wheel с PyPI;
# 2) иначе нативный wheel из репозитория Chaquopy (тот же ABI, что у нашего Python),
#    например pandas, pillow, scipy. Компилировать ничего не нужно.

CHAQUO_INDEXES = ["https://chaquo.com/pypi-13.1/"]
_BUNDLED = {"numpy", "requests", "certifi", "jedi", "parso", "pyflakes", "autopep8",
            "pycodestyle", "dulwich", "urllib3", "idna", "charset-normalizer"}
_done = set()


def _norm(n):
    return re.sub(r"[-_.]+", "-", n).lower()


def _open(url):
    ctx = None
    try:
        import ssl
        import certifi
        ctx = ssl.create_default_context(cafile=certifi.where())
    except Exception:
        pass
    return urllib.request.urlopen(url, timeout=60, context=ctx)


def _arch():
    import platform
    m = platform.machine().lower()
    return {"aarch64": "arm64_v8a", "arm64": "arm64_v8a", "x86_64": "x86_64"}.get(m, m)


def _native_url(n):
    from urllib.parse import urljoin, urldefrag
    tag = "cp%d%d" % sys.version_info[:2]
    arch = _arch()
    for base in CHAQUO_INDEXES:
        page = base + n + "/"
        try:
            with _open(page) as r:
                html = r.read().decode("utf-8", "replace")
        except Exception:
            continue
        cands = []
        for href in re.findall(r'href="([^"]+)"', html):
            url = urldefrag(urljoin(page, href))[0]
            fn = url.rsplit("/", 1)[-1]
            if (fn.endswith(".whl") and ("%s-%s-android_" % (tag, tag)) in fn
                    and fn.endswith("_%s.whl" % arch)):
                ver = tuple(int(x) for x in re.findall(r"\d+", fn.split("-")[1]))
                cands.append((ver, url))
        if cands:
            return max(cands)[1]
    return None


def _fetch_wheel(url):
    tmp = os.path.join(SITE, "_dl.whl")
    with _open(url) as r, open(tmp, "wb") as f:
        f.write(r.read())
    meta = ""
    try:
        with zipfile.ZipFile(tmp) as z:
            z.extractall(SITE)
            m = [x for x in z.namelist() if x.endswith(".dist-info/METADATA")]
            if m:
                meta = z.read(m[0]).decode("utf-8", "replace")
    finally:
        os.remove(tmp)
    return meta


def _deps(meta):
    out = []
    for line in meta.splitlines():
        if not line.strip():
            break
        if line.startswith("Requires-Dist:"):
            spec = line.split(":", 1)[1].strip()
            if ";" in spec:
                continue  # условные зависимости (extras, бэкпорты, Windows) пропускаем
            out.append(spec)
    return out


def _install(name, log, depth):
    n = _norm(re.split(r"[<>=!~\[;( ]", name.strip())[0])
    if not n or n in _done or depth > 6:
        return
    _done.add(n)
    if n in _BUNDLED:
        log.append("• %s уже встроен в приложение" % n)
        return
    url, ver, kind = None, "", "pure"
    try:
        with _open("https://pypi.org/pypi/%s/json" % n) as r:
            data = json.load(r)
        pure = [u for u in data["urls"] if u["filename"].endswith("none-any.whl")]
        if pure:
            url, ver = pure[0]["url"], data["info"]["version"]
    except Exception as e:
        log.append("%s: PyPI недоступен (%s)" % (n, e))
    if not url:
        url, kind = _native_url(n), "android"
    if not url:
        log.append("✗ %s: нет ни чисто-Python wheel, ни Android-сборки в репозитории Chaquopy. "
                   "Пакет придётся добавить в app/build.gradle.kts." % n)
        return
    meta = _fetch_wheel(url)
    log.append("✓ %s %s [%s]" % (n, ver or url.rsplit("/", 1)[-1], kind))
    for dep in _deps(meta):
        _install(dep, log, depth + 1)


def pip_install(spec):
    log = []
    try:
        for part in re.split(r"[\s,]+", spec.strip()):
            if part:
                _install(part, log, 0)
        importlib.invalidate_caches()
    except Exception as e:
        log.append("Ошибка: %s" % e)
    return "\n".join(log) or "ничего не установлено"


# ====================== Git (dulwich) ======================

def _s(x):
    return x.decode(errors="replace") if isinstance(x, bytes) else str(x)


def _repo_dir(rel):
    d = os.path.normpath(os.path.join(ROOT, rel or "."))
    if not (d == ROOT or d.startswith(ROOT + os.sep)):
        raise ValueError("папка вне проекта")
    return d


def _auth(url, user, token):
    if user and token and url.startswith("https://"):
        from urllib.parse import quote
        return "https://%s:%s@%s" % (quote(user, safe=""), quote(token, safe=""), url[8:])
    return url


def git(cmd, rel, a="", b="", c=""):
    try:
        from dulwich import porcelain
        from dulwich.repo import Repo
        path = _repo_dir(rel)

        if cmd == "init":
            os.makedirs(path, exist_ok=True)
            porcelain.init(path)
            gi = os.path.join(path, ".gitignore")
            if not os.path.exists(gi):
                with open(gi, "w") as f:
                    f.write("__pycache__/\n*.pyc\n")
            return "Репозиторий создан: " + (rel or ".")

        if cmd == "clone":
            url = a.strip()
            name = url.rstrip("/").split("/")[-1].removesuffix(".git") or "repo"
            target = os.path.join(ROOT, name)
            errs = io.BytesIO()
            porcelain.clone(_auth(url, b, c), target, errstream=errs, outstream=io.BytesIO())
            r = Repo(target)
            cfg = r.get_config()
            cfg.set((b"remote", b"origin"), b"url", url.encode())  # без токена на диске
            cfg.write_to_path()
            return "Клонировано в папку: " + name

        repo = Repo(path)

        if cmd == "remote":
            cfg = repo.get_config()
            cfg.set((b"remote", b"origin"), b"url", a.strip().encode())
            cfg.set((b"remote", b"origin"), b"fetch", b"+refs/heads/*:refs/remotes/origin/*")
            cfg.write_to_path()
            return "origin = " + a.strip()

        if cmd == "status":
            st = porcelain.status(repo)
            try:
                br = _s(porcelain.active_branch(repo))
            except Exception:
                br = "?"
            lines = ["ветка: " + br]
            for k, v in st.staged.items():
                for f in v:
                    lines.append("в индексе (%s): %s" % (k, _s(f)))
            for f in st.unstaged:
                lines.append("изменён: " + _s(f))
            for f in st.untracked:
                lines.append("новый: " + _s(f))
            return "\n".join(lines) if len(lines) > 1 else lines[0] + "\nнет изменений"

        if cmd == "commit":
            msg, name, email = a.strip(), b.strip(), c.strip()
            if not msg or not name or not email:
                return "Нужны сообщение, имя и email"
            st = porcelain.status(repo)
            paths = [_s(x) for x in st.unstaged] + [_s(x) for x in st.untracked]
            if paths:
                repo.stage(paths)
            author = ("%s <%s>" % (name, email)).encode()
            sha = porcelain.commit(repo, message=msg.encode(), author=author, committer=author)
            return "Коммит " + _s(sha)[:8]

        if cmd == "log":
            out = []
            for e in repo.get_walker(max_entries=30):
                cm = e.commit
                t = time.strftime("%Y-%m-%d %H:%M", time.localtime(cm.commit_time))
                first = _s(cm.message).strip().split("\n")[0]
                out.append("%s %s %s\n    %s" % (_s(cm.id)[:8], t, _s(cm.author), first))
            return "\n".join(out) or "коммитов нет"

        if cmd == "diff":
            try:
                tree = repo[repo.head()].tree
            except KeyError:
                return "коммитов ещё нет"
            st = porcelain.status(repo)
            files = [_s(x) for x in st.unstaged] + [_s(x) for x in st.staged.get("modify", [])]
            out = []
            for f in files:
                try:
                    _, sha = repo[tree].lookup_path(repo.object_store.__getitem__, f.encode())
                    old = _s(repo[sha].data).splitlines()
                except KeyError:
                    old = []
                full = os.path.join(path, f)
                new = open(full, encoding="utf-8", errors="replace").read().splitlines() \
                    if os.path.exists(full) else []
                out.extend(difflib.unified_diff(old, new, "a/" + f, "b/" + f, lineterm=""))
            return "\n".join(out) or "нет изменений"

        if cmd in ("push", "pull"):
            try:
                url = _s(repo.get_config().get((b"remote", b"origin"), b"url"))
            except KeyError:
                return "Не задан remote origin (кнопка Remote)"
            branch = porcelain.active_branch(repo)
            loc = _auth(url, a, b)
            errs = io.BytesIO()
            ref = [b"refs/heads/" + branch]
            if cmd == "push":
                porcelain.push(repo, loc, ref, outstream=io.BytesIO(), errstream=errs)
            else:
                porcelain.pull(repo, loc, ref, outstream=io.BytesIO(), errstream=errs)
            return ("Push" if cmd == "push" else "Pull") + " выполнен\n" + _s(errs.getvalue())

        return "неизвестная команда"
    except Exception as e:
        return "Ошибка git: %s: %s" % (type(e).__name__, e)
