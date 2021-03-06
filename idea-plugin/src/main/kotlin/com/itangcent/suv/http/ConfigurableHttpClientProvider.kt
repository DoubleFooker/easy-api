package com.itangcent.suv.http

import com.google.inject.Inject
import com.itangcent.idea.plugin.settings.SettingBinder
import com.itangcent.intellij.config.ConfigReader
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.config.SocketConfig
import org.apache.http.impl.client.HttpClients

class ConfigurableHttpClientProvider : AbstractHttpClientProvider() {

    @Inject(optional = true)
    val settingBinder: SettingBinder? = null

    @Inject(optional = true)
    val configReader: ConfigReader? = null

    override fun buildHttpClient(): HttpClient {
        val httpClientBuilder = HttpClients.custom()

        val config = readHttpConfig()

        httpClientBuilder
                .setDefaultSocketConfig(SocketConfig.custom()
                        .setSoTimeout(config.timeOut * 1000)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(config.timeOut * 1000)
                        .setConnectionRequestTimeout(config.timeOut * 1000)
                        .setSocketTimeout(config.timeOut * 1000)
                        .build())
        return httpClientBuilder.build()
    }

    private fun readHttpConfig(): HttpConfig {
        val httpConfig = HttpConfig()

        settingBinder?.read()?.let { setting ->
            httpConfig.timeOut = setting.httpTimeOut
        }


        if (configReader != null) {
            try {
                configReader.first("http.timeOut")?.toInt()
                        ?.let { httpConfig.timeOut = it }
            } catch (e: NumberFormatException) {
            }
        }

        return httpConfig
    }

    class HttpConfig {

        //default 10s
        var timeOut: Int = defaultHttpTimeOut
    }

    companion object {
        const val defaultHttpTimeOut: Int = 10
    }
}