package com.elysium.softwork.worker.forum.presentation.views.newpost

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.shared.presentation.components.SoftWorkCard
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.AccentWhite
import com.elysium.softwork.shared.presentation.theme.Danger
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky
import com.elysium.softwork.worker.forum.domain.model.Category
import com.elysium.softwork.worker.forum.presentation.viewmodel.CategorySelectionViewModel

/**
 * Intermediate step of the new-post flow: pick the category the new thread belongs to, or create
 * one inline (`POST /api/v1/categories` under the company forum). Categories are scoped to the
 * worker's organization by the store. Selecting a category forwards its `category_id` to the
 * composer.
 *
 * @param onBack pop handler for the header back arrow.
 * @param onCategoryChosen invoked with the selected `category_id` to open the composer.
 */
@Composable
fun CategorySelectionScreen(
    onBack: () -> Unit,
    onCategoryChosen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategorySelectionViewModel = viewModel(factory = CategorySelectionViewModel.Factory),
) {
    val state: CategorySelectionViewModel.UiState by viewModel.state.collectAsStateWithLifecycle()
    val isCreating: Boolean by viewModel.isCreating.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AccentWhite),
    ) {
        Header(onBack = onBack)

        when (val current = state) {
            CategorySelectionViewModel.UiState.Loading -> LoadingBlock()
            is CategorySelectionViewModel.UiState.Error -> ErrorBlock(message = current.message)
            is CategorySelectionViewModel.UiState.Ready -> {
                CategoryList(
                    categories = current.categories,
                    onCategoryChosen = onCategoryChosen,
                    modifier = Modifier.weight(1f),
                )
                // The inline composer is only usable once a forum id is resolved.
                if (current.forumId != null) {
                    NewCategoryBar(
                        enabled = !isCreating,
                        onCreate = viewModel::createCategory,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.cd_back),
            tint = PrimaryNavy,
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onBack),
        )
        Text(
            text = stringResource(R.string.category_selection_title),
            color = PrimaryNavy,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = PrimarySky)
    }
}

@Composable
private fun ErrorBlock(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = Danger,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CategoryList(
    categories: List<Category>,
    onCategoryChosen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
    ) {
        if (categories.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.category_selection_empty),
                    color = AccentDark,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
        items(items = categories, key = { it.category_id ?: 0L }) { category ->
            CategoryCard(
                category = category,
                onClick = { category.category_id?.let(onCategoryChosen) },
            )
        }
    }
}

@Composable
private fun CategoryCard(category: Category, onClick: () -> Unit) {
    SoftWorkCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = category.title.orEmpty(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = PrimaryNavy,
                fontSize = 15.sp,
            )
            if (!category.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentDark,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun NewCategoryBar(enabled: Boolean, onCreate: (String) -> Unit) {
    var text: String by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(color = AccentWhite, shape = RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    text = stringResource(R.string.category_new_hint),
                    color = AccentDark.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = PrimaryNavy, fontSize = 14.sp),
                cursorBrush = SolidColor(PrimarySky),
            )
        }
        val canCreate: Boolean = enabled && text.isNotBlank()
        Text(
            text = stringResource(R.string.category_create_button),
            color = if (canCreate) PrimarySky else PrimarySky.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clickable(enabled = canCreate) {
                    onCreate(text)
                    text = ""
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}
