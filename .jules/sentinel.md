
## $(date +%Y-%m-%d) - Arabic text normalization consistently applied
**Learning:** Some views (`HistoryScreen`, `ImportBookScreen`, `BookTOC`) were performing case-insensitive `.contains()` queries against user input without passing the text and the query through `normalizeArabic()` first, violating the rule that all search queries must be normalized.
**Action:** When filtering or searching texts where the data may contain Arabic, always import `com.maktabah.utils.normalizeArabic` and apply it to both the query string and the source property being compared to ensure exact matching and consistency.
