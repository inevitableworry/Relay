// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** First-boot Portal-style setup without a vendor account, forced pairing,
 * or resetting the user's existing Android network/launcher settings.
 * Languages below localize the *setup wizard*. Full Relay strings remain an
 * explicit follow-up so an English fallback is never mislabeled as complete. */
class PortalSetupActivity:AppCompatActivity() {
    private data class Language(val tag:String,val name:String,
        val intro:String,val wifi:String,val controller:String,
        val console:String,val finish:String,val next:String) {
        val stepNames get()=listOf(intro,wifi,controller,console,finish)
    }
    companion object {
        private val languages=listOf(
            Language("en-US","English (United States)","Select your language",
                "Connect to Wi-Fi","Connect a controller","Connect to your PS5",
                "Setup complete","Continue"),
            Language("en-GB","English (United Kingdom)","Select your language",
                "Connect to Wi-Fi","Connect a controller","Connect to your PS5",
                "Setup complete","Continue"),
            Language("fr","Français","Sélectionnez votre langue",
                "Connectez-vous au Wi-Fi","Connectez une manette","Connectez-vous à votre PS5",
                "Configuration terminée","Continuer"),
            Language("es-ES","Español (España)","Selecciona tu idioma",
                "Conéctate a Wi-Fi","Conecta un mando","Conecta tu PS5",
                "Configuración completada","Continuar"),
            Language("es-419","Español (Latinoamérica)","Selecciona tu idioma",
                "Conéctate a Wi-Fi","Conecta un control","Conéctate a tu PS5",
                "Configuración completa","Continuar"),
            Language("de","Deutsch","Wähle deine Sprache",
                "Mit WLAN verbinden","Controller verbinden","Mit deiner PS5 verbinden",
                "Einrichtung abgeschlossen","Weiter"),
            Language("pt-BR","Português (Brasil)","Selecione seu idioma",
                "Conectar ao Wi-Fi","Conectar um controle","Conectar ao seu PS5",
                "Configuração concluída","Continuar"),
            Language("it","Italiano","Seleziona la tua lingua",
                "Connettiti al Wi-Fi","Collega un controller","Connettiti alla PS5",
                "Configurazione completata","Continua"),
            Language("ja","日本語","言語を選択してください",
                "Wi-Fiに接続","コントローラーを接続","PS5に接続",
                "設定が完了しました","続ける"),
            Language("ko","한국어","언어를 선택하세요",
                "Wi-Fi에 연결","컨트롤러 연결","PS5에 연결",
                "설정 완료","계속"),
            Language("zh-CN","简体中文","选择语言",
                "连接 Wi-Fi","连接控制器","连接 PS5",
                "设置完成","继续"),
            Language("zh-TW","繁體中文","選取語言",
                "連線至 Wi-Fi","連接控制器","連線至 PS5",
                "設定完成","繼續"),
            Language("ar","العربية","اختر لغتك",
                "الاتصال بشبكة Wi-Fi","توصيل وحدة التحكم","الاتصال بجهاز PS5",
                "اكتمل الإعداد","متابعة"),
            Language("cs","Čeština","Vyberte jazyk",
                "Připojit k Wi-Fi","Připojit ovladač","Připojit k PS5",
                "Nastavení dokončeno","Pokračovat"),
            Language("da","Dansk","Vælg dit sprog",
                "Opret forbindelse til Wi-Fi","Tilslut controller","Opret forbindelse til PS5",
                "Opsætning fuldført","Fortsæt")
        )
    }
    private val prefs by lazy {getSharedPreferences("portal_first_setup",Context.MODE_PRIVATE)}
    private lateinit var page:PortalPage
    private var step=0
    private var language=0
    private val current get()=languages[language]

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        PortalUi.fullscreen(this)
        step=savedInstanceState?.getInt("step") ?: 0
        language=languages.indexOfFirst {it.tag==prefs.getString("language","en-US")}
            .takeIf {it>=0} ?: 0
        page=PortalPage(this,"",::previous)
        setContentView(page.root)
        render()
    }
    private fun previous() {
        if(step>0) {step--;render()} else finish()
    }
    private fun row(title:String,description:String="",onClick:()->Unit)=
        page.add(PortalUi.row(this,title,description,onClick=onClick))
    private fun render() {
        page.clear(current.stepNames[step])
        when(step) {
            0 -> {
                page.note("Choose the language used during first-time setup.")
                languages.forEachIndexed {index,item->
                    val entry=PortalUi.row(this,item.name,if(index==language) "✓" else "") {
                        language=index
                        prefs.edit().putString("language",current.tag).apply()
                        render()
                    }
                    // Sony's language selector uses a fine illuminated
                    // rounded outline around the current locale.
                    entry.isSelected=index==language
                    page.add(entry)
                }
            }
            1 -> {
                page.note("Wi-Fi is required for Remote Play. Choose a network using Android's secure connection panel.")
                row("Connect to Wi-Fi","Android will request your approval") {
                    startActivity(Intent(if(android.os.Build.VERSION.SDK_INT>=29)
                        "android.settings.panel.action.WIFI" else Settings.ACTION_WIFI_SETTINGS))
                }
                row("Network Status","View current connection without leaving Relay") {
                    PortalNetworkStatusDialog(this).show()
                }
            }
            2 -> {
                page.note("Pair a Bluetooth or USB gamepad, or use the on-screen controls.")
                row("Pair Bluetooth Controller","Android pairing confirmation is required") {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                row("Button Mapping","View the PS5-style controller and assign controls") {
                    startActivity(Intent(this,PortalGamepadRemapActivity::class.java))
                }
            }
            3 -> {
                page.note("Remote Play JB and Traditional Remote Play are available. You can register a PS5 later in Settings.")
                row("Remote Play Settings","Select a streaming mode and PS5 address") {
                    startActivity(Intent(this,PortalSettingsActivity::class.java)
                        .putExtra(PortalSettingsActivity.EXTRA_PAGE,"remote"))
                }
            }
            else -> {
                page.note("Relay is ready. It does not require a PlayStation login for setup, and it does not collect credentials here.")
                page.note("The language preference is kept on this device. Additional Relay translations will be provided as they are reviewed.")
            }
        }
        page.add(PortalUi.primary(this,if(step==4) "Finish" else current.next) {
            if(step==4) complete() else {step++;render()}
        })
        if(step in 1..3) page.add(PortalUi.row(this,"Skip for Now") {step++;render()})
    }
    private fun complete() {
        prefs.edit().putBoolean("completed",true)
            .putString("language",current.tag).apply()
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(current.tag))
        if(!isFinishing) {
            startActivity(Intent(this,PortalMainActivity::class.java))
            finish()
        }
    }
    override fun onSaveInstanceState(out:Bundle) {out.putInt("step",step);super.onSaveInstanceState(out)}
}
