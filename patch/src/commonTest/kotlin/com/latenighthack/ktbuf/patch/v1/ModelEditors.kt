package com.latenighthack.ktbuf.patch.v1

import com.latenighthack.ktbuf.ProtobufWriter
import com.latenighthack.ktbuf.patch.EditContext
import com.latenighthack.ktbuf.patch.EditorList
import com.latenighthack.ktbuf.patch.Patch

// Hand-written stand-in for what the retired `protoc-gen-kt-patch` plugin used to
// emit for model.proto. Each editor wraps an EditContext and turns property
// assignments / nested blocks into Change entries; `edit` collects them into a
// Patch. Field numbers mirror model.proto.

fun RootMessage.edit(builder: RootMessageEditor.() -> Unit): Patch {
    val context = EditContext()

    RootMessageEditor(this, context).apply(builder)

    return Patch(context.changes)
}

class RootMessageEditor(
    private val target: RootMessage,
    private val context: EditContext,
) {
    var anInt: Int = target.anInt
        set(value) {
            field = value
            context.replace(3, value = value) { v, fieldNumber -> encode(v, fieldNumber) }
        }

    var str: String = target.str
        set(value) {
            field = value
            context.replace(4, value = value) { v, fieldNumber -> encode(v, fieldNumber) }
        }

    var type: RootMessage.Type = target.type
        set(value) {
            field = value
            context.replace(5, value = value.value) { v, fieldNumber -> encode(v, fieldNumber) }
        }

    fun inner(builder: RootMessage_InnerMessageEditor.() -> Unit) {
        val scoped = context.createScopedContext(1)

        scoped.createDefault()

        RootMessage_InnerMessageEditor(target.inner ?: RootMessage.InnerMessage(), scoped).apply(builder)
    }

    fun repeatMe(builder: RepeatMeListEditor.() -> Unit) {
        RepeatMeListEditor(context, target.repeatMe).apply(builder)
    }
}

class RootMessage_InnerMessageEditor(
    private val target: RootMessage.InnerMessage,
    private val context: EditContext,
) {
    var str: String = target.str
        set(value) {
            field = value
            context.replace(1, value = value) { v, fieldNumber -> encode(v, fieldNumber) }
        }

    var anInt: Int = target.anInt
        set(value) {
            field = value
            context.replace(2, value = value) { v, fieldNumber -> encode(v, fieldNumber) }
        }

    fun innerInner(builder: RootMessage_InnerInnerMessageEditor.() -> Unit) {
        val scoped = context.createScopedContext(3)

        scoped.createDefault()

        RootMessage_InnerInnerMessageEditor(
            target.innerInner ?: RootMessage.InnerInnerMessage(),
            scoped,
        ).apply(builder)
    }
}

class RootMessage_InnerInnerMessageEditor(
    private val target: RootMessage.InnerInnerMessage,
    private val context: EditContext,
) {
    var str: String = target.str
        set(value) {
            field = value
            context.replace(5, value = value) { v, fieldNumber -> encode(v, fieldNumber) }
        }

    var anInt: Int = target.anInt
        set(value) {
            field = value
            context.replace(6, value = value) { v, fieldNumber -> encode(v, fieldNumber) }
        }
}

class RepeatMeListEditor(
    context: EditContext,
    list: List<RootMessage.RepeatedMessage>,
) : EditorList<RootMessage.RepeatedMessage>(context, 2, { toByteArray() }, list) {
    fun add(builder: RootMessage_RepeatedMessageBuilder.() -> Unit) {
        add(RootMessage_RepeatedMessageBuilder().apply(builder).build())
    }
}
