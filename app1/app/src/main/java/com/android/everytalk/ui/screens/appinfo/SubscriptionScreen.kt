package com.android.everytalk.ui.screens.appinfo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.everytalk.R

/** 仅展示套餐；点击订阅给出未开放提示，不修改账号权益或发起支付。 */
@Composable
fun SubscriptionScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var requestedPlan by rememberSaveable { mutableStateOf<String?>(null) }
    // 当前套餐暂时使用 UI 默认值；接入订阅接口后替换为服务端返回的套餐名即可。
    val currentPlan = "Free"
    BackHandler(onBack = onBack)

    // 复用管理页面的滚动容器和上下渐变，内容自然延伸到透明系统栏下。
    ImmersiveInfoPage(
        title = stringResource(R.string.subscription_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "subscription_intro") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.subscription_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.subscription_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showDetails = true }) {
                    Text(stringResource(R.string.subscription_details_action))
                }
            }
        }
        item(key = "free") {
            SubscriptionPlanCard(
                name = "Free",
                price = stringResource(R.string.subscription_free_price),
                credit = null,
                isCurrentPlan = currentPlan == "Free",
                onSubscribe = null,
            )
        }
        item(key = "pro") {
            SubscriptionPlanCard(
                name = "Pro",
                price = stringResource(R.string.subscription_pro_price),
                credit = stringResource(R.string.subscription_pro_credit),
                isCurrentPlan = currentPlan == "Pro",
                onSubscribe = { requestedPlan = "Pro" },
            )
        }
        item(key = "max") {
            SubscriptionPlanCard(
                name = "Max",
                price = stringResource(R.string.subscription_max_price),
                credit = stringResource(R.string.subscription_max_credit),
                isCurrentPlan = currentPlan == "Max",
                onSubscribe = { requestedPlan = "Max" },
            )
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.subscription_details_action)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SubscriptionExplanation(
                        stringResource(R.string.subscription_models_heading),
                        stringResource(R.string.subscription_models_body),
                    )
                    SubscriptionExplanation(
                        stringResource(R.string.subscription_pricing_heading),
                        stringResource(R.string.subscription_pricing_body),
                    )
                    SubscriptionExplanation(
                        stringResource(R.string.subscription_cache_heading),
                        stringResource(R.string.subscription_cache_body),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
    requestedPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { requestedPlan = null },
            title = { Text(stringResource(R.string.subscription_unavailable_title)) },
            text = { Text(stringResource(R.string.subscription_unavailable_body, plan)) },
            confirmButton = {
                TextButton(onClick = { requestedPlan = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

/** 套餐价格使用人民币，模型额度单独显示美元单位，避免两种金额混在一起。 */
@Composable
private fun SubscriptionPlanCard(
    name: String,
    price: String,
    credit: String?,
    isCurrentPlan: Boolean,
    onSubscribe: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(price, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                if (credit != null) {
                    Text(stringResource(R.string.subscription_month), color = colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
            if (credit == null) {
                Text(stringResource(R.string.subscription_free_benefit), style = MaterialTheme.typography.bodyLarge)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.subscription_credit_label),
                        style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    Text(credit, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.subscription_model_summary),
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (isCurrentPlan) {
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    enabled = false,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = colors.surfaceContainerHighest,
                        disabledContentColor = colors.onSurfaceVariant,
                    ),
                ) {
                    Text(stringResource(R.string.subscription_current_subscription))
                }
            } else if (onSubscribe != null) {
                Button(
                    onClick = onSubscribe,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.onBackground,
                        contentColor = colors.background,
                    ),
                ) {
                    Text(stringResource(R.string.subscription_action, name))
                }
            } else {
                Text(stringResource(R.string.subscription_free_action),
                    style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SubscriptionExplanation(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
