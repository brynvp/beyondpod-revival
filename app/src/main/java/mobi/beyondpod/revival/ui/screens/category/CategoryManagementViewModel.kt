package mobi.beyondpod.revival.ui.screens.category

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mobi.beyondpod.revival.data.local.entity.CategoryEntity
import mobi.beyondpod.revival.data.repository.CategoryRepository
import mobi.beyondpod.revival.domain.usecase.category.CreateCategoryUseCase
import mobi.beyondpod.revival.domain.usecase.category.DeleteCategoryUseCase
import javax.inject.Inject

sealed interface CategoryManagementUiState {
    data object Loading : CategoryManagementUiState
    data class Success(val categories: List<CategoryEntity>) : CategoryManagementUiState
}

/**
 * ViewModel for CategoryManagementScreen.
 *
 * Deletion is safe — feeds are NEVER cascaded (CLAUDE.md rule #7, §7.3):
 *   [DeleteCategoryUseCase] removes cross-ref rows and nullifies primaryCategoryId /
 *   secondaryCategoryId on affected feeds. Those feeds then appear under "Uncategorized".
 */
@HiltViewModel
class CategoryManagementViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val createCategoryUseCase: CreateCategoryUseCase,
    private val deleteCategoryUseCase: DeleteCategoryUseCase
) : ViewModel() {

    val uiState: StateFlow<CategoryManagementUiState> = categoryRepository.getAllCategories()
        .map<List<CategoryEntity>, CategoryManagementUiState> { CategoryManagementUiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CategoryManagementUiState.Loading
        )

    // New category name input (used in create dialog)
    var newCategoryName by mutableStateOf("")
        private set

    fun onNewCategoryNameChange(value: String) { newCategoryName = value }

    /** Create a new category with the current [newCategoryName]. */
    fun createCategory() {
        val name = newCategoryName.trim()
        if (name.isBlank()) return
        viewModelScope.launch {
            createCategoryUseCase(CategoryEntity(name = name))
            newCategoryName = ""
        }
    }

    /** Rename an existing category. */
    fun renameCategory(category: CategoryEntity, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || trimmed == category.name) return
        viewModelScope.launch {
            categoryRepository.updateCategory(category.copy(name = trimmed))
        }
    }

    /**
     * Safely delete a category.
     *
     * Feeds in the deleted category are moved to Uncategorized — they are
     * NEVER deleted (§7.3, CLAUDE.md rule #7).
     */
    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            deleteCategoryUseCase(categoryId)
        }
    }

    /**
     * Reorder categories. Assigns a new sortOrder to each category based on the
     * position in [reorderedList].
     */
    fun reorderCategories(reorderedList: List<CategoryEntity>) {
        viewModelScope.launch {
            reorderedList.forEachIndexed { index, category ->
                if (category.sortOrder != index) {
                    categoryRepository.reorderCategory(category.id, index)
                }
            }
        }
    }
}
