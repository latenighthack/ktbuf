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
        if (index < 0) return false
        removeAt(index)
        return true
    }

    override fun removeAt(index: Int): T {
        val existing = backingList[index] // Validate before recording.
        context.remove(fieldNumber, index)
        backingList.removeAt(index)
        return existing
    }

    override fun set(index: Int, element: T): T {
        val existing = backingList[index]
        val encoded = com.latenighthack.ktbuf.ProtobufOutputStream.encode {
            encode(fieldNumber) { encodeRaw(element.writer()) }
        }
        context.changes.add(Change.Replace(context.pathPrefix.appending(fieldNumber, index), encoded, emptyList()))
        backingList[index] = element
        return existing
    }

    override fun add(index: Int, element: T) {
        if (index < 0 || index > size) throw IndexOutOfBoundsException("Invalid insertion index: $index")
        val encoded = element.writer()
        context.insert(fieldNumber, index, encoded)
        backingList.add(index, element)
    }

    fun addDistinct(element: T) {
        if (!backingList.contains(element)) add(size, element)
    }
}
