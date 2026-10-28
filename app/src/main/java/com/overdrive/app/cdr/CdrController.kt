package com.overdrive.app.cdr

import android.content.Context
import com.overdrive.app.launcher.AdbDaemonLauncher

/**
 * SLIM-CN: “停用原厂记录仪”开关的执行层。
 *
 * 背景（DiPlus 实测分析）：比亚迪自带记录仪 com.byd.cdr 会占用摄像头 HAL
 * 的单客户端通道，导致第三方应用打不开全景/环视流；DiPlus 带了一个
 * “停用原厂 CDR”开关（pm disable-user com.byd.cdr），停掉原厂记录仪后
 * 自己接管摄像头。这是 DiPlus 在汉上能用、OverDrive 不能用的头号嫌疑。
 *
 * 执行通道：复用应用已有的无线 ADB 通路 —— AdbDaemonLauncher
 * .executeShellCommand → AdbShellExecutor → 本地 5555 无线 ADB（shell
 * UID 2000，与电脑上 `adb shell pm disable-user` 同等权限）。不新开
 * 权限、不改守护进程结构。
 *
 * 前置条件（缺一不可，否则命令失败、调用方应把开关回弹）：
 *  1. 车机无线 ADB 已开启并授权；
 *  2. OverDrive 守护进程已起来（ADB 通道由 DaemonStartupManager 维护）。
 *
 * 持久性：pm disable-user 是系统级持久状态，重启后仍保持停用；关闭开关
 * 时执行 pm enable 恢复原厂记录仪。开关本身的状态存在应用私有
 * SharedPreferences（slim_cdr_prefs），仅用于界面回显，不作为系统状态
 * 的真源 —— 系统真源以 `pm list packages -d` 为准。
 *
 * 注意：停用后需重启摄像头守护进程（或重进摄像头映射）让 HAL 重新
 * 释放/认领摄像头；本类不自动重启守护进程，避免在用户不知情时动录像链路。
 */
object CdrController {

    /** 比亚迪自带记录仪包名（DiPlus 开关里用的就是这个包）。 */
    const val PACKAGE_CDR = "com.byd.cdr"

    private const val PREFS = "slim_cdr_prefs"
    private const val KEY_DISABLED = "cdr_disabled"

    /** 界面回显用的开关状态（默认关闭）。 */
    fun isDisabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLED, false)

    /**
     * 执行一次切换。成功后持久化开关状态；失败时不写状态，由调用方回弹 UI。
     * 回调跑在 ADB 执行线程，调用方需自行切回主线程更新界面。
     */
    fun apply(
        context: Context,
        disable: Boolean,
        callback: (success: Boolean, error: String?) -> Unit,
    ) {
        val command = if (disable) {
            "pm disable-user $PACKAGE_CDR"
        } else {
            "pm enable $PACKAGE_CDR"
        }
        try {
            AdbDaemonLauncher(context).executeShellCommand(
                command,
                object : AdbDaemonLauncher.LaunchCallback {
                    override fun onLog(message: String) = Unit
                    override fun onLaunched() {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_DISABLED, disable)
                            .apply()
                        callback(true, null)
                    }

                    override fun onError(error: String) {
                        callback(false, error)
                    }
                },
            )
        } catch (t: Throwable) {
            callback(false, t.message)
        }
    }
}
