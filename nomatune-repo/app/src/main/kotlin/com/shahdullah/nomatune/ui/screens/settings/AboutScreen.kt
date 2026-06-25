/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.shahdullah.nomatune.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.shahdullah.nomatune.BuildConfig
import com.shahdullah.nomatune.R
import com.shahdullah.nomatune.ui.component.IconButton
import com.shahdullah.nomatune.ui.utils.backToMain
import com.shahdullah.nomatune.LocalPlayerAwareWindowInsets
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import com.shahdullah.nomatune.currentBuildHash
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import androidx.compose.material3.CircularProgressIndicator
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.headers
import org.json.JSONObject

data class TeamMember(
    val avatarUrl: String,
    val name: String,
    val position: String,
    val profileUrl: String? = null,
    val github: String? = null,
    val website: String? = null,
    val discord: String? = null

)

@Composable
private fun OutlinedIconChip(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier
                .padding(8.dp)
                .size(20.dp),
        )
    }
}

@Composable
fun OutlinedIconChipMembers(
    iconRes: Int,
    contentDescription: String?,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = Modifier.size(32.dp),
        shapes = ButtonDefaults.shapes(),
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.secondary,
                shape = CircleShape,
            )
            .padding(
                horizontal = 6.dp,
                vertical = 2.dp,
            ),
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val httpClient = remember { HttpClient() }
    DisposableEffect(Unit) {
        onDispose { httpClient.close() }
    }
    val nightlyBuildHash = currentBuildHash
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val leadDeveloper = TeamMember(
        avatarUrl = "https://avatars.githubusercontent.com/u/267626413?v=4",
        name = "Shahdullah",
        position = stringResource(R.string.about_position_lead_dev),
        profileUrl = "https://github.com/Shahdullah",
        github = "https://github.com/Shahdullah",
        website = "https://nomatune.vercel.app",
        discord = "https://discord.com/users/886971572668219392"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(innerPadding)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                    )
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .heightIn(max = 16.dp)
            )

            Image(
                painter = painterResource(R.drawable.about_splash),
                contentDescription = null,
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(Color.Black)
                    .clickable { },
            )

            Row(
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "NomaTune",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                AboutBadge(text = BuildConfig.VERSION_NAME)

                nightlyBuildHash?.let {
                    Spacer(Modifier.width(4.dp))
                    AboutBadge(text = it)
                }

                Spacer(Modifier.width(4.dp))

                if (BuildConfig.DEBUG) {
                    AboutBadge(text = "DEBUG")
                } else {
                    AboutBadge(text = BuildConfig.ARCHITECTURE.uppercase())
                }
            }

            Spacer(Modifier.height(8.dp))

            Row {
                IconButton(
                    onClick = { uriHandler.openUri("https://github.com/Shahdullah/NomaTune") },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.github),
                        contentDescription = stringResource(R.string.about_content_desc_github)
                    )
                }

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = { uriHandler.openUri("https://nomatune.vercel.app") },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.website),
                        contentDescription = stringResource(R.string.about_content_desc_website)
                    )
                }

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = { uriHandler.openUri("https://t.me/NomaTuneGC") },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.telegram),
                        contentDescription = stringResource(R.string.about_content_desc_telegram)
                    )
                }

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = { uriHandler.openUri("https://www.buymeachai.in/NomaTune") },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.coffee),
                        contentDescription = stringResource(R.string.about_content_desc_donate)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        uriHandler.openUri("https://www.buymeachai.in/NomaTune")
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.coffee),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Buy Me a Chai")
                }

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            isCheckingUpdate = true
                            try {
                                val response = httpClient.get("https://api.github.com/repos/Shahdullah/NomaTune/releases/latest") {
                                    headers { append("Accept", "application/vnd.github+json") }
                                }
                                val body = response.bodyAsText()
                                val json = JSONObject(body)
                                val tagName = json.optString("tag_name", "")
                                val htmlUrl = json.optString("html_url", "")
                                val latestVersion = tagName.removePrefix("v")
                                if (latestVersion.isNotBlank() && latestVersion != BuildConfig.VERSION_NAME && htmlUrl.isNotBlank()) {
                                    uriHandler.openUri(htmlUrl)
                                } else {
                                    Toast.makeText(context, "You're up to date!", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Failed to check for updates", Toast.LENGTH_SHORT).show()
                            } finally {
                                isCheckingUpdate = false
                            }
                        }
                    },
                    enabled = !isCheckingUpdate,
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Check for Updates")
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionHeader(
                title = stringResource(R.string.about_lead_developer),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(Modifier.height(8.dp))

            LeadDeveloperCard(
                member = leadDeveloper,
                onOpenUri = uriHandler::openUri,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(24.dp))

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun LeadDeveloperCard(
    member: TeamMember,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = member.avatarUrl,
                contentDescription = member.name,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = member.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = member.position,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                member.github?.let { url ->
                    OutlinedIconChip(
                        iconRes = R.drawable.github,
                        contentDescription = stringResource(R.string.about_content_desc_github),
                        onClick = { onOpenUri(url) },
                    )
                }

                member.website?.takeIf { it.isNotBlank() }?.let { url ->
                    OutlinedIconChip(
                        iconRes = R.drawable.website,
                        contentDescription = stringResource(R.string.about_content_desc_website),
                        onClick = { onOpenUri(url) },
                    )
                }

                member.discord?.let { url ->
                    OutlinedIconChip(
                        iconRes = R.drawable.alternate_email,
                        contentDescription = stringResource(R.string.about_content_desc_discord),
                        onClick = { onOpenUri(url) },
                    )
                }
            }
        }
    }
}
