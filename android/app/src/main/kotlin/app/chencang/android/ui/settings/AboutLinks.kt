package app.chencang.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import app.chencang.shared.R

/**
 * spec §2.4：设置 → 关于 →「服务器源码」。Custom Tab 是系统浏览器组件，只有用户点击
 * 才打开页面；App 自己不发这个请求（不算 MediaTransport 例外，也不在守卫名单里）。
 */
object AboutLinks {
    /** [site] 来自签名配置的 shareSite，以 `/` 结尾。 */
    fun sourceUrl(site: String): String = site + "source"

    fun openSource(context: Context, site: String) = open(context, sourceUrl(site))

    /** 隐私政策页（官网静态页，与 [sourceUrl] 同一个站点）。 */
    fun privacyUrl(site: String): String = site + "privacy.html"

    fun openPrivacy(context: Context, site: String) = open(context, privacyUrl(site))

    fun openSite(context: Context, site: String) = open(context, site)

    private fun open(context: Context, url: String) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, Uri.parse(url))
        } catch (e: ActivityNotFoundException) {
            // 没装任何浏览器/Custom Tabs 实现的机器上 launchUrl 会抛这个——提示一句,不崩溃。
            Toast.makeText(context, context.getString(R.string.settings_no_browser), Toast.LENGTH_SHORT).show()
        }
    }
}
