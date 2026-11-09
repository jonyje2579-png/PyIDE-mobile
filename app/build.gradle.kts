plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.pyide"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pyide"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            // Пакеты, которые будут доступны в приложении через import.
            // Добавляй свои сюда и пересобирай.
            install("requests")
            // Свежий numpy с PyPI не имеет Android-колеса и ломает сборку.
            // 1.26.2 есть на индексе Chaquopy для Python 3.11.
            install("numpy==1.26.2")
            install("certifi")
            install("jedi")          // автодополнение, переход к определению
            install("pyflakes")      // проверка ошибок на лету
            install("autopep8")      // форматирование кода
            install("dulwich==0.21.7") // Git
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
