## 2025-07-20 - [Enforce Global Text Normalization for Search]
**Learning:** Hardcoding duplicate normalization logic, such as manual string iteration or custom filtering (e.g. `!it.isArabicHarakat() && it.code != 0x0640`), violates the DRY principle and creates subtle discrepancies in search results when they don't identically match global standards.
**Action:** Always use the globally established utility function, such as `String.normalizeArabic()`, across all components, Views, and ViewModel layers to maintain consistency with the database and cross-platform behaviors.
