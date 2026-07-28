package com.viwa.android.ui.screens.service

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ServiceScreen(
    onBack: () -> Unit,
    viewModel: ServiceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val authorized = viewModel.isServiceMenuAuthorized()

    LaunchedEffect(authorized) {
        if (!authorized) onBack()
    }

    if (!authorized) return

    LaunchedEffect(Unit) {
        if (ServiceScreenLaunch.selectDashboardOnOpen) {
            ServiceScreenLaunch.selectDashboardOnOpen = false
            viewModel.onServiceGroupSelected(ViwaServiceGroupId.Dashboard)
        }
        if (ServiceScreenLaunch.selectEquipmentDevicesOnOpen) {
            ServiceScreenLaunch.selectEquipmentDevicesOnOpen = false
            viewModel.onServiceGroupSelected(ViwaServiceGroupId.Equipment)
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val parentTypography = MaterialTheme.typography
    val scaledTypography = remember(parentTypography) { parentTypography.scaled() }

    MaterialTheme(colorScheme = colorScheme, typography = scaledTypography) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .semantics { testTagsAsResourceId = true }
                    .testTag(ServiceMenuTestTags.SERVICE_MENU_ROOT)
                    .background(MaterialTheme.colorScheme.background),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ViwaServiceMenuHeader(
                    onClose = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onBack()
                    },
                )

                Row(modifier = Modifier.weight(1f)) {
                    ViwaServiceMenuGroupRail(
                        groups = ViwaServiceMenuGroups,
                        selectedGroupId = state.selectedServiceGroupId,
                        onGroupSelected = viewModel::onServiceGroupSelected,
                    )

                    ViwaServiceMenuContent(
                        state = state,
                        viewModel = viewModel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ViwaServiceMenuHeader(onClose: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Сервисное меню",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.headlineSmall,
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier.testTag(ServiceMenuTestTags.SERVICE_MENU_CLOSE),
        ) {
            Text("✕", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ViwaServiceMenuGroupRail(
    groups: List<ViwaServiceGroupSpec>,
    selectedGroupId: ViwaServiceGroupId,
    onGroupSelected: (ViwaServiceGroupId) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(172.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()),
    ) {
        groups.forEach { group ->
            val selected = group.id == selectedGroupId
            NavigationRailItem(
                selected = selected,
                onClick = { onGroupSelected(group.id) },
                icon = {},
                label = {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                },
                colors =
                    NavigationRailItemDefaults.colors(
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .testTag(ServiceMenuTestTags.serviceGroupTag(group.id)),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViwaServiceMenuContent(
    state: ServiceUiState,
    viewModel: ServiceViewModel,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val group = findViwaServiceGroup(state.selectedServiceGroupId)
    val keyboardHost = remember { ServiceKeyboardHostController() }

    LaunchedEffect(state.selectedServiceGroupId, state.selectedServiceSubTabId) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        keyboardHost.dismiss()
    }

    Column(modifier = modifier.fillMaxHeight()) {
        if (group.subTabs.size > 1) {
            val selectedIndex = group.subTabIndexOf(state.selectedServiceSubTabId).coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                edgePadding = 0.dp,
            ) {
                group.subTabs.forEachIndexed { index, spec ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { viewModel.onServiceSubTabSelected(spec.id) },
                        modifier = Modifier.testTag(ServiceMenuTestTags.serviceSubTabTag(spec.id)),
                        text = {
                            Text(
                                spec.label,
                                color =
                                    if (index == selectedIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                    )
                }
            }
        }

        CompositionLocalProvider(LocalServiceKeyboardHost provides keyboardHost) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .imePadding(),
                ) {
 // Нижний слой: тап вне интерактивных детей — сброс фокуса и панели (не на том же узле, что поля).
                    Box(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    keyboardHost.dismiss()
                                },
                            )
                        },
                    )
                    ViwaServiceMenuTabContent(state = state, viewModel = viewModel)
                }
                ServiceKeyboardBottomPanel(controller = keyboardHost)
            }
        }
    }
}
