## 2024-05-18 - Native Memory Leaks from SoftReference in JNI

**Learning:** Using `SoftReference` for Kotlin objects that wrap native pointers (like `ZstdDecompressCtx` or JNI contexts) is a dangerous anti-pattern. When memory pressure occurs, the JVM Garbage Collector clears the `SoftReference` and destroys the Kotlin object, but it *does not* call the `.close()` method predictably. This leads to native C/C++ memory leaking over time because the native pointer is never freed, eventually causing Out-Of-Memory (OOM) crashes in the native heap which are hard to debug.

**Action:** Replaced `ConcurrentLinkedQueue<SoftReference<T>>` with a bounded `ArrayBlockingQueue<T>`. When the queue is full, `offer()` returns false, and we can explicitly call `.close()` on the evicted context to safely dispose of the C memory.
## 2023-10-27 - [@Immutable in Compose]
**Learning:** Only apply `@Immutable` to truly immutable data classes (all `val`, no mutable collections). `BooksData`, `SearchResult`, and `Annotation` meet these criteria. Faking immutability leads to recomposition bugs that are harder to debug than the initial performance issues. Fading Edge optimizations via `drawWithContent` were already correctly implemented, avoiding unnecessary composition reads.
**Action:** Always manually verify every field of a data class before applying `@Immutable`. Replaced static integers with `ids.xml` items in Compose-to-View wrappers to pass lint checks.
## 2025-07-15 - [LibraryDataManager buildAuthorHierarchy Performance Optimization]
**Learning:** Avoid redundant traversals of large collections.
**Action:** When grouping collections and performing transformations based on predicates on those elements, attempt to combine them into one loop.
