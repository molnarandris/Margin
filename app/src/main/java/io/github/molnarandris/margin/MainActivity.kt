package io.github.molnarandris.margin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.molnarandris.margin.ui.home.HomeScreen
import io.github.molnarandris.margin.ui.pdfviewer.PdfViewerScreen
import io.github.molnarandris.margin.ui.settings.SettingsScreen
import android.net.Uri
import android.provider.DocumentsContract
import io.github.molnarandris.margin.ui.theme.MarginTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MarginTheme {
                MarginApp()
            }
        }
    }
}

@Composable
fun MarginApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenPdf = { dirUri, docUri ->
                    val encodedDir = Uri.encode(dirUri.toString())
                    val encodedDocId = Uri.encode(DocumentsContract.getDocumentId(docUri))
                    navController.navigate("pdf_viewer?dirUri=$encodedDir&docId=$encodedDocId")
                },
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = "pdf_viewer?dirUri={dirUri}&docId={docId}",
            arguments = listOf(
                navArgument("dirUri") { type = NavType.StringType },
                navArgument("docId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val dirUri = Uri.parse(backStackEntry.arguments?.getString("dirUri") ?: return@composable)
            val docId = backStackEntry.arguments?.getString("docId") ?: return@composable
            PdfViewerScreen(
                dirUri = dirUri,
                docId = docId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
