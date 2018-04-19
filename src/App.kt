package it.menzani.cryptocharts

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.treeToValue
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.binding.DoubleBinding
import javafx.beans.property.DoubleProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.util.Duration
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL
import java.util.*
import java.util.concurrent.*

class App : Application() {
    private lateinit var mapper: ObjectMapper
    private lateinit var executor: ExecutorService
    private var setup: Setup? = null
    private var suppressed: Exception? = null
    private lateinit var fetcher: Future<Fetcher.Result>
    private lateinit var mediaPlayer: MediaPlayer // Do not inline: https://stackoverflow.com/a/47837424/3453226
    private lateinit var presentation: Presentation

    override fun init() {
        mapper = jacksonObjectMapper()
        executor = Executors.newSingleThreadExecutor()
        try {
            setup = loadSetup()
        } catch (e: IOException) {
            suppressed = e
        }
        refresh()
        playSong()
    }

    private fun loadSetup(): Setup {
        val external = File(parameters.named.getOrDefault("setup-file", "setup.json"))
        val internal = javaClass.getResource(external.name)
        return if (internal == null) mapper.readValue(external) else mapper.readValue(internal)
    }

    @Synchronized
    private fun refresh() {
        fetcher = executor.submit(Fetcher(suppressed, setup, mapper))
    }

    private fun playSong() {
        val song = javaClass.getResource("song.mp3") ?: return
        val media = Media(song.toExternalForm())
        media.setOnError { media.error.printStackTrace() }
        mediaPlayer = MediaPlayer(media)
        mediaPlayer.setOnError { mediaPlayer.error.printStackTrace() }
        mediaPlayer.play()
    }

    override fun start(primaryStage: Stage) {
        primaryStage.icons.add(Image(javaClass.getResourceAsStream("icon.png")))
        primaryStage.title = "Crypto Charts"
        primaryStage.isResizable = false
        primaryStage.fullScreenExitHint = ""
        presentation = Presentation(primaryStage)

        schedule(Duration.minutes(10.0)) {
            if (primaryStage.scene == null) {
                primaryStage.scene = Scene(createPane())
                primaryStage.show()
                presentation.registerEvents()
            } else {
                refresh()
                primaryStage.scene.root = createPane()
            }
            primaryStage.sizeToScene()
        }
        primaryStage.iconifiedProperty().addListener(ChangeListener { _, _, newValue ->
            if (newValue) return@ChangeListener
            primaryStage.scene.root = createPane()
        })
    }

    private fun createPane(): Pane {
        val pane = StackPane(createText())
        pane.padding = Insets(10.0)
        return pane
    }

    private fun createText(): Text {
        val fetcherResult: Fetcher.Result
        try {
            fetcherResult = synchronized(this) {
                return@synchronized fetcher.get()
            }
        } catch (e: ExecutionException) {
            e.printStackTrace()
            val text = Text(e.printStackTraceString())
            text.fill = Color.RED
            return text
        }

        val textContent = StringBuilder()
        var totalNetWorth = 0.0
        val localCurrencyFormatter = fetcherResult.localCurrency.formatter
        for (currency in fetcherResult.currencies) {
            textContent.append(currency.toString(localCurrencyFormatter))
            textContent.append(System.lineSeparator())
            totalNetWorth += currency.netWorth
        }
        textContent.append("Total Net Worth: ")
        textContent.append(localCurrencyFormatter.format(totalNetWorth))

        val text = Text(textContent.toString())
        text.font = Font.loadFont(javaClass.getResourceAsStream("SourceSansPro/SourceSansPro-Light.otf"), presentation.defaultFontSize)
        text.styleProperty().bind(Bindings.concat("-fx-font-size: ", presentation.fontSizeProperty.asString(), "px;")) // https://stackoverflow.com/a/23832850/3453226
        return text
    }

    override fun stop() {
        executor.shutdown()
    }

    private class Presentation(private val stage: Stage) : ChangeListener<Boolean>, EventHandler<KeyEvent> {
        private lateinit var scalingFontSize: DoubleBinding
        private val shortcut: KeyCombination = KeyCodeCombination(KeyCode.F11)
        val defaultFontSize = 16.0
        val fontSizeProperty: DoubleProperty = SimpleDoubleProperty(defaultFontSize)

        fun registerEvents() {
            val scene = stage.scene
            scalingFontSize = scene.widthProperty().add(scene.heightProperty()).divide(30)
            stage.fullScreenProperty().addListener(this)
            scene.addEventFilter(KeyEvent.KEY_PRESSED, this)
        }

        override fun changed(observable: ObservableValue<out Boolean>, oldValue: Boolean, newValue: Boolean) {
            if (newValue) {
                fontSizeProperty.bind(scalingFontSize)
            } else {
                fontSizeProperty.unbind()
                fontSizeProperty.set(defaultFontSize)
            }
        }

        override fun handle(event: KeyEvent) {
            if (!shortcut.match(event)) return
            event.consume()
            stage.isFullScreen = true
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Application.launch(App::class.java, *args)
        }
    }
}

private fun schedule(interval: Duration, action: () -> Unit) {
    Platform.runLater(action)
    val animation = Timeline(KeyFrame(interval, EventHandler { action() }))
    animation.cycleCount = Timeline.INDEFINITE
    animation.play()
}

private fun ExecutionException.printStackTraceString(): String {
    val writer = StringWriter()
    writer.use { this.cause!!.printStackTrace(PrintWriter(it)) }
    return writer.toString()
}

class Fetcher(private val suppressed: Exception?, private val setup: Setup?, private val mapper: ObjectMapper) : Callable<Fetcher.Result> {
    override fun call(): Result {
        if (suppressed != null) throw suppressed

        val currencies: MutableList<Currency> = mutableListOf()
        for (currencyOwned in setup!!.currenciesOwned) {
            val tree = fetch(currencyOwned.id, setup.localCurrency.id.toUpperCase())
            val currency: Currency = mapper.treeToValue(tree)
            val amount = if (currencyOwned.id == "stellar" && currencyOwned.stellarAccountId != null) {
                Arrays.stream(currencyOwned.stellarAccount().balances)
                        .filter { it.assetType == "native" }
                        .findFirst().get()
                        .balance.toDouble()
            } else {
                currencyOwned.amount
            }
            val price = tree["price_${setup.localCurrency.id.toLowerCase()}"].asDouble()
            currency.netWorth = amount * price
            currencies.add(currency)
        }
        return Result(currencies, setup.localCurrency)
    }

    private fun fetch(currencyId: String, localCurrencyId: String): JsonNode {
        val response: JsonNode = mapper.readTree(URL(
                "https://api.coinmarketcap.com/v1/ticker/$currencyId/?convert=$localCurrencyId"))
        if (response.isArray && response.size() == 1) {
            return response[0]
        }
        throw Exception("Unexpected response: $response")
    }

    class Result(val currencies: List<Currency>, val localCurrency: LocalCurrency)
}