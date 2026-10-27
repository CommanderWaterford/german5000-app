import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = G5000Config.COMPILE_SDK
            defaultConfig {
                minSdk = G5000Config.MIN_SDK
                targetSdk = G5000Config.TARGET_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = G5000Config.JAVA_VERSION
                targetCompatibility = G5000Config.JAVA_VERSION
            }
        }
        pluginManager.apply("g5000.android.compose")
    }
}
