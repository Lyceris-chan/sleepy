package dev.sleepy.app.engine

object RamBuffer {
    /**
     * Checks if enough JVM heap is available to process APK completely in RAM.
     * Rule: Available heap must be > 3x the APK size.
     */
    fun shouldUseHeap(apkSizeBytes: Long): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = runtime.maxMemory() - usedMemory
        return availableMemory > (apkSizeBytes * 3)
    }
}
