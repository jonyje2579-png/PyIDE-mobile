package com.pyide

import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject

data class Completion(val name: String, val rest: String, val type: String)
data class Problem(val line: Int, val col: Int, val msg: String)
data class DefLoc(val path: String, val line: Int, val col: Int)
data class Var(val name: String, val type: String, val repr: String)
data class StackItem(val file: String, val line: Int, val fn: String)
data class PauseInfo(
    val id: Int, val file: String, val line: Int,
    val stack: List<StackItem>, val vars: List<Var>
)
data class RunState(val running: Boolean, val waitingInput: Boolean, val pause: PauseInfo?)

object Backend {
    private val mod by lazy { Python.getInstance().getModule("ide_backend") }

    fun init(root: String, site: String) { mod.callAttr("init", root, site) }

    // запуск и отладка
    fun start(path: String, debug: Boolean) { mod.callAttr("start", path, debug) }
    fun drain(): String = mod.callAttr("drain").toString()
    fun sendInput(line: String) { mod.callAttr("send_input", line) }
    fun stop() { mod.callAttr("stop") }
    fun dbgCmd(c: String) { mod.callAttr("dbg_cmd", c) }
    fun dbgEval(e: String): String = mod.callAttr("dbg_eval", e).toString()
    fun setBreaks(path: String, lines: Collection<Int>) {
        mod.callAttr("set_breaks", path, lines.joinToString(","))
    }

    fun state(): RunState {
        val o = JSONObject(mod.callAttr("state").toString())
        var p: PauseInfo? = null
        if (!o.isNull("pause")) {
            val q = o.getJSONObject("pause")
            val st = q.getJSONArray("stack")
            val vs = q.getJSONArray("vars")
            p = PauseInfo(
                q.getInt("id"), q.getString("file"), q.getInt("line"),
                (0 until st.length()).map {
                    val s = st.getJSONObject(it)
                    StackItem(s.getString("file"), s.getInt("line"), s.getString("fn"))
                },
                (0 until vs.length()).map {
                    val a = vs.getJSONArray(it)
                    Var(a.getString(0), a.getString(1), a.getString(2))
                }
            )
        }
        return RunState(o.getBoolean("running"), o.getBoolean("input"), p)
    }

    // терминал
    fun replReset() { mod.callAttr("repl_reset") }
    fun replPush(line: String): Pair<String, Boolean> {
        val o = JSONObject(mod.callAttr("repl_push", line).toString())
        return o.getString("out") to o.getBoolean("more")
    }

    // умный редактор
    fun lint(src: String, path: String): List<Problem> {
        val a = JSONArray(mod.callAttr("lint", src, path).toString())
        return (0 until a.length()).map {
            val r = a.getJSONArray(it)
            Problem(r.getInt(0), r.getInt(1), r.getString(2))
        }
    }

    fun complete(src: String, line: Int, col: Int, path: String): List<Completion> {
        val a = JSONArray(mod.callAttr("complete", src, line, col, path).toString())
        return (0 until a.length()).map {
            val r = a.getJSONArray(it)
            Completion(r.getString(0), r.getString(1), r.getString(2))
        }
    }

    fun goto(src: String, line: Int, col: Int, path: String): List<DefLoc> {
        val a = JSONArray(mod.callAttr("goto", src, line, col, path).toString())
        return (0 until a.length()).map {
            val r = a.getJSONArray(it)
            DefLoc(r.getString(0), r.getInt(1), r.getInt(2))
        }
    }

    fun format(src: String): String = mod.callAttr("format_code", src).toString()

    // пакеты и git
    fun pip(spec: String): String = mod.callAttr("pip_install", spec).toString()
    fun git(cmd: String, dir: String, a: String = "", b: String = "", c: String = ""): String =
        mod.callAttr("git", cmd, dir, a, b, c).toString()
}
