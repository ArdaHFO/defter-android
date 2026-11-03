package dev.defter.app.localization

import dev.defter.app.R

internal data class QuantityEntry(val resource: Int, val englishOne: String, val englishOther: String)

internal object QuantityCatalog {
    val entries: Map<String, QuantityEntry> = mapOf(
        "%1\$s defter" to QuantityEntry(R.plurals.quantity_notebooks, "%1\$s notebook", "%1\$s notebooks"),
        "%1\$s klasör" to QuantityEntry(R.plurals.quantity_folders, "%1\$s folder", "%1\$s folders"),
        "%1\$s sayfa" to QuantityEntry(R.plurals.quantity_pages, "%1\$s page", "%1\$s pages"),
        "%1\$s sayfa · %2\$s" to QuantityEntry(R.plurals.quantity_page_details, "%1\$s page · %2\$s", "%1\$s pages · %2\$s"),
        "%1\$s nesne" to QuantityEntry(R.plurals.quantity_objects, "%1\$s object", "%1\$s objects"),
        "SAYFA İÇERİĞİ · %1\$s sonuç" to QuantityEntry(R.plurals.quantity_search_results, "PAGE CONTENT · %1\$s result", "PAGE CONTENT · %1\$s results"),
        "%1\$d defter kalıcı olarak silindi." to QuantityEntry(R.plurals.quantity_notebooks_deleted, "%1\$d notebook permanently deleted.", "%1\$d notebooks permanently deleted."),
        "%1\$d ses kaydı kalıcı olarak silindi." to QuantityEntry(R.plurals.quantity_audio_deleted, "%1\$d recording permanently deleted.", "%1\$d recordings permanently deleted."),
        "%1\$d ses kaydı ve transkripti kalıcı olarak silinecek. Bu işlem geri alınamaz." to QuantityEntry(R.plurals.quantity_confirm_audio, "%1\$d recording and its transcript will be permanently deleted. This cannot be undone.", "%1\$d recordings and their transcripts will be permanently deleted. This cannot be undone."),
        "%1\$d defter ve sayfaları kalıcı olarak silinecek. Bağlı ses kayıtları korunur, silinen sayfalara bağlantıları kaldırılır. Bu işlem geri alınamaz." to QuantityEntry(R.plurals.quantity_confirm_notebooks, "%1\$d notebook and its pages will be permanently deleted. Linked audio recordings are kept, and their links to deleted pages are removed. This cannot be undone.", "%1\$d notebooks and their pages will be permanently deleted. Linked audio recordings are kept, and their links to deleted pages are removed. This cannot be undone.")
    )
}
