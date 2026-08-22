package com.latenighthack.ktbuf.patch

open class EditorList<T>(
    protected val context: EditContext,
    private val fieldNumber: Int,
    private val writer: T.() -> ByteArray,
    list: List<T>,
): AbstractMutableList<T>() {
    private val backingList = list.toMutableList()

    override val size: Int
        get() = backingList.size

    override fun get(index: Int): T {
        return backingList.get(index)
    }

    override fun remove(element: T): Boolean {
        val index = backingList.indexOf(element)

        if (index < 0) {
            return false
        }

        val bytesToMatch = element.writer()
        context.remove(fieldNumber, bytesToMatch)

        // Remove from the backing list directly: routing through super.remove()
        // would dispatch to the overridden removeAt() and emit a second,
        // index-based change on top of the distinct one.
        backingList.removeAt(index)

        return true
    }

    override fun removeAt(index: Int): T {
        context.remove(fieldNumber, index)

        return backingList.removeAt(index)
    }

    override fun set(index: Int, element: T): T {
        // context replace
        return backingList.set(index, element)
    }

    override fun add(index: Int, element: T) {
        context.insert(fieldNumber, index, element.writer())
        return backingList.add(index, element)
    }

    fun addDistinct(element: T) {
//        context.insert(fieldNumber, index, element.writer())
//        return backingList.add(index, element)
    }
}
