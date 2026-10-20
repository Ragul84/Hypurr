package com.ragul84.hypurr.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.ragul84.hypurr.data.ThemeMode
import com.ragul84.hypurr.model.MapCall
import com.ragul84.hypurr.model.MapElement
import com.ragul84.hypurr.model.MapPage
import com.ragul84.hypurr.model.PageMapData
import com.ragul84.hypurr.model.RestructureApplied
import com.ragul84.hypurr.model.RestructureMove
import com.ragul84.hypurr.model.RestructurePlan
import com.ragul84.hypurr.model.Trace
import com.ragul84.hypurr.model.TraceCall
import com.ragul84.hypurr.model.TraceClick
import com.ragul84.hypurr.model.TraceCodeRef
import com.ragul84.hypurr.model.TraceReply
import com.ragul84.hypurr.model.TraceRoute
import com.ragul84.hypurr.ui.theme.HypurrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Trace a click, the page map and the restructure plan (the React + Express fixture's data). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class TraceMapScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, start: TraceMapUiState) {
        var theme by mutableStateOf(ThemeMode.Light)
        var st by mutableStateOf(start)
        compose.setContent { HypurrTheme(theme) { TraceMapScreen(st, onChange = { st = it }) } }
        for (mode in listOf(ThemeMode.Light, ThemeMode.Dark)) {
            theme = mode
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("${System.getProperty("roborazzi.output.dir")}/android-$name-${mode.name.lowercase()}.png")
        }
    }

    private val orders = TraceRoute("Express", "POST", "/api/orders", "server/routes/orders.js", 7, "placeOrder")

    private val trace = Trace(
        id = "a1b2c3", url = "http://localhost:5173/orders", status = "done", browser = "msedge",
        clicks = listOf(
            TraceClick(
                label = "Place order", tag = "button", page = "/orders", handlerName = "handlePlaceOrder",
                handler = TraceCodeRef("handlePlaceOrder", "client/src/pages/Orders.jsx", 9, "source map"),
                calls = listOf(
                    TraceCall("POST", "http://localhost:5173/api/orders", 201, "Created", 84,
                        requestBody = "{\n  \"sku\": \"SOCK-RED\",\n  \"qty\": 2,\n  \"email\": \"[redacted]\"\n}",
                        responseBody = "{\n  \"id\": 41,\n  \"status\": \"placed\"\n}",
                        sentFrom = TraceCodeRef("createOrder", "client/src/api.js", 9, "source map"), handledBy = orders),
                    TraceCall("GET", "http://localhost:5173/api/orders", 500, "Internal Server Error", 312,
                        responseBody = "{\n  \"error\": \"db timeout\"\n}",
                        sentFrom = TraceCodeRef("listOrders", "client/src/api.js", 18, "source map"),
                        handledBy = TraceRoute("Express", "GET", "/api/orders", "server/routes/orders.js", 5)),
                ),
                appeared = listOf("Order 41 placed"),
            ),
        ),
        blockedHosts = listOf("www.google-analytics.com"),
        chain = listOf(listOf(
            "You clicked \"Place order\" on /orders.",
            "The click runs handlePlaceOrder in client/src/pages/Orders.jsx:9.",
            "It calls POST /api/orders → 201 Created in 84 ms. Sent by createOrder in client/src/api.js:9. Handled by Express POST /api/orders in server/routes/orders.js:7 (placeOrder).",
            "It calls GET /api/orders → 500 Internal Server Error in 312 ms. Sent by listOrders in client/src/api.js:18.",
            "Then the page shows \"Order 41 placed\".",
        )),
    )

    private val map = PageMapData(
        router = "React Router",
        pages = listOf(
            MapPage("/", "client/src/Home.jsx", "Home", listOf("client/src/Home.jsx", "client/src/api.js"),
                listOf(MapElement("input", "Search socks", null, "client/src/Home.jsx", 12)),
                listOf(MapCall("GET", "/api/products", "client/src/api.js", 4, "listProducts", TraceRoute("Express", "GET", "/api/products", "server/index.js", 8)))),
            MapPage("/orders", "client/src/pages/Orders.jsx", "Orders",
                listOf("client/src/pages/Orders.jsx", "client/src/api.js", "client/src/components/OrderRow.jsx"),
                listOf(MapElement("button", "Place order", "handlePlaceOrder", "client/src/pages/Orders.jsx", 21),
                    MapElement("button", "Refresh", "handleRefresh", "client/src/pages/Orders.jsx", 22)),
                listOf(MapCall("GET", "/api/orders", "client/src/api.js", 18, "listOrders", TraceRoute("Express", "GET", "/api/orders", "server/routes/orders.js", 5)),
                    MapCall("POST", "/api/orders", "client/src/api.js", 9, "createOrder", orders))),
            MapPage("/orders/:id", "client/src/OrderDetail.jsx", "OrderDetail", listOf("client/src/OrderDetail.jsx"),
                listOf(MapElement("form", "Form", "cancel", "client/src/OrderDetail.jsx", 11), MapElement("button", "Cancel order", null, "client/src/OrderDetail.jsx", 12)),
                listOf(MapCall("DELETE", "/api/orders/:p", "client/src/OrderDetail.jsx", 8, null, TraceRoute("Express", "DELETE", "/api/orders/:id", "server/routes/orders.js", 13)))),
        ),
        routes = List(4) { orders },
        unusedRoutes = listOf(TraceRoute("Express", "GET", "/api/admin/export", "server/routes/admin.js", 3)),
    )

    private val plan = RestructurePlan(
        root = "client/src",
        moves = listOf(
            RestructureMove("client/src/Home.jsx", "client/src/pages/Home.jsx", "it's a page the router opens"),
            RestructureMove("client/src/OrderDetail.jsx", "client/src/pages/OrderDetail.jsx", "it's a page the router opens"),
            RestructureMove("client/src/api.js", "client/src/api/api.js", "it holds the API calls"),
        ),
        notes = listOf("OrderDetail.jsx makes API calls itself; moving them into client/src/api/ would make it easier to find."),
        testCommand = "npm test",
    )

    private val base = TraceMapUiState(project = "/home/ravi/work/sock-shop", projectName = "sock-shop")

    @Test fun traceStart() = shoot("trace-start", base.copy(url = "http://localhost:5173/orders"))

    @Test fun traceApproval() = shoot("trace-approval", base.copy(url = "https://shop.example.com/orders",
        approval = TraceReply("needsApproval", host = "shop.example.com", hostKind = "other",
            why = "shop.example.com isn't localhost and doesn't look like staging. Only allow it if it's a test site you're meant to use.")))

    @Test fun traceRecording() = shoot("trace-recording", base.copy(trace = trace.copy(status = "recording", clicks = emptyList())))

    @Test fun traceResult() = shoot("trace-result", base.copy(trace = trace))

    @Test fun traceCallOpen() = shoot("trace-call-open", base.copy(trace = trace, open = "0.0"))

    @Test fun pageMap() = shoot("pagemap-list", base.copy(tab = 1, map = map, plan = plan))

    @Test fun pageDetail() = shoot("pagemap-page", base.copy(tab = 1, map = map, plan = plan, mapStep = MapStep.Page, page = "/orders"))

    @Test fun plan() = shoot("restructure-plan", base.copy(tab = 1, map = map, plan = plan, mapStep = MapStep.Plan))

    @Test fun planConfirm() = shoot("restructure-confirm", base.copy(tab = 1, map = map, plan = plan, mapStep = MapStep.Plan, confirmApply = true))

    @Test fun planReady() = shoot("restructure-ready", base.copy(tab = 1, map = map, plan = plan, mapStep = MapStep.Plan,
        applied = RestructureApplied("ready", "hypurr/restructure-20261020-143012", "main", 3, 3, "npm test")))

    @Test fun planFailed() = shoot("restructure-failed", base.copy(tab = 1, map = map, plan = plan, mapStep = MapStep.Plan,
        applied = RestructureApplied("testsFailed", "hypurr/restructure-20261020-143012", "main", 3, 3, "npm test",
            testOutput = "FAIL src/pages/Home.test.jsx\n  Cannot find module '../api' from 'src/pages/Home.jsx'\nTests: 1 failed, 11 passed")))

    @Test
    fun tappingAPageOpensItAndApplyAsksFirst() {
        var st by mutableStateOf(base.copy(tab = 1, map = map, plan = plan))
        compose.setContent { HypurrTheme(ThemeMode.Light) { TraceMapScreen(st, onChange = { st = it }) } }
        compose.onNodeWithText("/orders").performClick()
        assertEquals(MapStep.Page, st.mapStep)
        compose.onNodeWithText("Place order").assertIsDisplayed()
        st = st.copy(mapStep = MapStep.Plan)
        compose.onNodeWithText("Apply on a new branch").performClick()
        assertEquals(true, st.confirmApply)
        compose.onNodeWithText("Move 3 files on a new branch?").assertIsDisplayed()
    }

    @Test
    fun pathsAndSteps() {
        assertEquals("/api/orders?x=1", tracePathOf("http://localhost:5173/api/orders?x=1"))
        assertEquals(listOf("Place order", "Refresh"), base.copy(steps = " Place order\n\nRefresh ").stepList)
    }
}
