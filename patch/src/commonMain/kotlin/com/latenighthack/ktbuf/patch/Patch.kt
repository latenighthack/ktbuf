package com.latenighthack.ktbuf.patch

import com.latenighthack.ktbuf.ProtobufInputStream
import com.latenighthack.ktbuf.ProtobufOutputStream
import com.latenighthack.ktbuf.ProtobufReader
import com.latenighthack.ktbuf.ProtobufWriter
import com.latenighthack.ktbuf.patch.Path.Component

data class Path(val components: List<Component>) {
    data class Component(val fieldNumber: Int, val index: Int)

    fun appending(component: Component): Path = Path(components + component)

    fun appending(fieldNumber: Int, index: Int): Path = appending(Component(fieldNumber, index))

    fun appending(fieldNumber: Int): Path = appending(fieldNumber, 0)
}

interface Editor<T> {
    val value: T
    fun copy(): Editor<T>
}

sealed class Change {
    data class Insert(val path: Path, val message: ByteArray) : Change() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true

            other as Insert

            if (path != other.path) return false
            if (!message.contentEquals(other.message)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = path.hashCode()
            result = 31 * result + message.contentHashCode()
            return result
        }
    }

    data class Require(val path: Path) : Change()

    data class Replace(val path: Path, val message: ByteArray, val skipFieldNumbers: List<Int>) : Change() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as Replace

            if (path != other.path) return false
            if (!message.contentEquals(other.message)) return false
            if (skipFieldNumbers != other.skipFieldNumbers) return false

            return true
        }

        override fun hashCode(): Int {
            var result = path.hashCode()
            result = 31 * result + message.contentHashCode()
            result = 31 * result + skipFieldNumbers.hashCode()
            return result
        }
    }

    data class Remove(val path: Path) : Change()

    data class RemoveAll(val path: Path) : Change()

    data class InsertDistinct(
        val path: Path, val message: ByteArray,
        val matchPath: Path?, val matchMessage: ByteArray?
    ) : Change()

    data class RemoveDistinct(
        val path: Path, val message: ByteArray,
        val matchPath: Path?, val matchMessage: ByteArray?
    ) : Change()
}

data class EditContext(
    val changes: MutableList<Change> = mutableListOf(),
    val pathPrefix: Path = Path(emptyList()),
) {
    fun createScopedContext(fieldId: Int, index: Int = 0): EditContext {
        return EditContext(changes, pathPrefix.appending(Path.Component(fieldId, index)))
    }

    fun <T> replace(fieldNumber: Int, skipFieldNumbers: List<Int> = listOf(), value: T, encode: ProtobufWriter.(T, Int) -> Unit) {
        changes.add(Change.Replace(
            pathPrefix.appending(fieldNumber, 0),
            ProtobufOutputStream.encode { encode(value, fieldNumber) },
            skipFieldNumbers
        ))
    }

    fun replace(fieldNumber: Int, skipFieldNumbers: List<Int> = listOf(), value: ByteArray) {
        changes.add(Change.Replace(
            pathPrefix.appending(fieldNumber, 0),
            value,
            skipFieldNumbers
        ))
    }

    fun <T> insert(fieldNumber: Int, value: T, encode: ProtobufWriter.(T, Int) -> Unit) {
        changes.add(Change.Insert(
            pathPrefix.appending(fieldNumber, 0),
            ProtobufOutputStream.encode { encode(value, fieldNumber) }
        ))
    }

    fun remove(fieldNumber: Int, index: Int) {
        changes.add(Change.Remove(
            pathPrefix.appending(fieldNumber, index)
        ))
    }

    fun remove(fieldNumber: Int, bytesToMatch: ByteArray) {
        changes.add(Change.RemoveDistinct(
            pathPrefix.appending(fieldNumber),
            bytesToMatch,
            null,
            null
        ))
    }

    fun insert(fieldNumber: Int, index: Int, value: ByteArray) {
        changes.add(Change.Insert(
            pathPrefix.appending(fieldNumber, index),
            value
        ))
    }

    fun createDefault(index: Int = -1) {
        if (pathPrefix.components.isEmpty()) {
            return
        }

        val currentPath = pathPrefix.components.last()

        currentPath.fieldNumber

        if (index < 0) {
            changes.add(Change.Require(
                pathPrefix.appending(currentPath.fieldNumber, currentPath.index)
            ))
        } else {
            changes.add(index, Change.Require(
                pathPrefix.appending(currentPath.fieldNumber, currentPath.index)
            ))
        }
    }
}

data class Patch(val changes: List<Change>)

fun ByteArray.applyChanges(changes: List<Change>): ByteArray {
    var currentState = this

    for (change in changes) {
        currentState = currentState.applyChange(change)
    }

    return currentState
}

private fun ProtobufReader.visitField(writer: ProtobufWriter, remainingComponents: List<Component>, visitor: (ProtobufWriter, ByteArray?) -> Unit) {
    val component = remainingComponents.first()

    nextField()

    var isDone = false
    var remainingSkips = component.index

    while (remainingSkips >= 0) {
        if (currentFieldNumber == component.fieldNumber && isByteAvailable()) {
            if (remainingSkips > 0) {
                val bytes = skipField()

                writer.encodeRaw(bytes)

                nextField()
            } else {
                if (remainingComponents.size > 1) {
                    // found, drill down
                    readField { reader ->
                        writer.encode(component.fieldNumber) {
                            reader.visitField(this, remainingComponents.drop(1), visitor)
                        }
                    }
                } else {
                    // found, replace
                    visitor(writer, skipField())
                }

                isDone = true

                nextField()
            }

            remainingSkips -= 1
        } else if (remainingSkips == 0) {
            // not found, insert empty message and continue down but write a fake inner payload
            if (remainingComponents.size > 1) {
                readField { reader ->
                    writer.encode(component.fieldNumber) {
                        // drill down and continue to write
                        reader.visitField(this, remainingComponents.drop(1), visitor)
                    }
                }
            } else {
                // insert our payload
                visitor(writer, null)
            }

            remainingSkips -= 1
        }

        // skip and copy anything before our field of interest
        while ((currentFieldNumber != component.fieldNumber || isDone) && isByteAvailable()) {
            val bytes = skipField()

            writer.encodeRaw(bytes)

            nextField()
        }
    }
}

// Removes every occurrence of the terminal (length-delimited) field whose payload
// equals `matchMessage`, drilling through any leading path components verbatim.
private fun ProtobufReader.visitDistinct(writer: ProtobufWriter, remainingComponents: List<Component>, matchMessage: ByteArray) {
    val component = remainingComponents.first()

    if (remainingComponents.size > 1) {
        // Copy everything as-is, descending into the first matching container.
        while (isByteAvailable()) {
            nextField()

            if (currentFieldNumber == component.fieldNumber) {
                readField { reader ->
                    writer.encode(component.fieldNumber) {
                        reader.visitDistinct(this, remainingComponents.drop(1), matchMessage)
                    }
                }
            } else {
                writer.encodeRaw(skipField())
            }
        }

        return
    }

    while (isByteAvailable()) {
        nextField()

        if (currentFieldNumber == component.fieldNumber) {
            val payload = readBytes()

            if (!payload.contentEquals(matchMessage)) {
                writer.encode(component.fieldNumber) {
                    encodeRaw(payload)
                }
            }
        } else {
            writer.encodeRaw(skipField())
        }
    }
}

private fun ProtobufReader.applyChange(change: Change): ByteArray {
    val output = ProtobufOutputStream()

    output.write { writer ->
        when (change) {
            is Change.Insert ->
                visitField(writer, change.path.components) { writer, existing ->
                    writer.encode(change.path.components.last().fieldNumber) {
                        encodeRaw(change.message)
                    }
                    if (existing != null) {
                        writer.encodeRaw(existing)
                    }
                }
            is Change.Remove ->
                visitField(writer, change.path.components) { writer, _ ->
                    // skip writing anything
                }
            is Change.RemoveAll -> TODO()
            is Change.Require -> {
                visitField(writer, change.path.components) { writer, existing ->
                    if (existing != null) {
                        writer.encode(change.path.components.last().fieldNumber) {
                        }
                    }
                }
            }
            is Change.Replace -> {
                visitField(writer, change.path.components) { writer, _ ->
                    writer.encodeRaw(change.message)
                }
            }

            is Change.InsertDistinct -> TODO()
            is Change.RemoveDistinct ->
                visitDistinct(writer, change.path.components, change.message)
        }
    }

    return output.toByteArray()
}

fun ByteArray.applyChange(change: Change): ByteArray {
    val inputStream = ProtobufInputStream(size)

    inputStream.addBytes(this)

    return inputStream.read {
        // what if it's missing?

        // read until one step before the last fieldNumber
        //   write that content to the output byte array
        // read the size of the field and the header and save them to a side buffer
        // read the fields up to the field number we're looking for
        // read the header of the field we're replacing, note the size
        // skip the field we're replacing

        // read the bytes until the end
        it.applyChange(change)
    }
}
