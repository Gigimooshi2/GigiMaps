package app.morphe.patches.maps

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element
import java.util.logging.Logger

// GigiMaps: button in the place sheet header that opens GigiKav with a bus route there.
// Anchored on debug strings Maps keeps across releases, not obfuscated names:
//   "Placemark ["                     – the place model's toString()
//   "PlacesheetHeaderViewModelImpl"   – the place-sheet header view model
private const val KAV_EXTENSION = "Lapp/morphe/extension/maps/patches/KavButtonPatch;"
private const val KAV_PACKAGE = "uk.noammm.kav"
private const val PLACEMARK_MARKER = "Placemark ["
private const val HEADER_MARKER = "PlacesheetHeaderViewModelImpl"
private const val MAPS_ACTIVITY = "Lcom/google/android/maps/MapsActivity;"

private val kavLogger = Logger.getLogger("app.morphe.patches.maps.KavButtonPatch")

private val kavQueriesPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            val children = manifest.childNodes
            var queries: Element? = null
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element && node.tagName == "queries") queries = node
            }
            val q = queries ?: document.createElement("queries").also {
                manifest.insertBefore(it, manifest.firstChild)
            }
            val packages = q.getElementsByTagName("package")
            val exists = (0 until packages.length).any {
                (packages.item(it) as Element).getAttribute("android:name") == KAV_PACKAGE
            }
            if (!exists) {
                q.appendChild(document.createElement("package").also {
                    it.setAttribute("android:name", KAV_PACKAGE)
                })
            }
        }
    }
}

@Suppress("unused")
val kavButtonPatch = bytecodePatch(
    name = "Kav bus route button",
    description = "Adds a button next to Share on a place that opens GigiKav with a bus route to it.",
    default = true,
) {
    compatibleWith(compatibility)
    // Supplies the extension classes, and patched Maps doesn't run without it anyway.
    dependsOn(googleMapsMicroGPatch, kavQueriesPatch)

    execute {
        hookPlaceAssignments()
        hookActivity()
    }
}

private fun Method.hasString(value: String) = implementation?.instructions?.any { ins ->
    ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string == value
} == true

private fun BytecodePatchContext.hookPlaceAssignments() {
    val classes = getAllClassesWithStrings()
        .filterNot { it.type.startsWith("Lapp/morphe/") }
        .toList()

    val placemarkTypes = classes.filter { cls ->
        cls.methods.any { it.name == "toString" && it.parameterTypes.isEmpty() && it.hasString(PLACEMARK_MARKER) }
    }.map { it.type }.toSet()
    if (placemarkTypes.isEmpty()) throw PatchException("Placemark class not found")
    kavLogger.info("Placemark class(es): $placemarkTypes")

    val headers = classes.filter { cls -> cls.methods.any { it.hasString(HEADER_MARKER) } }
    if (headers.size != 1) {
        throw PatchException("Expected one place-sheet header view model, found ${headers.map { it.type }}")
    }
    val header = mutableClassDefBy(headers.single())
    kavLogger.info("Place-sheet header view model: ${header.type}")

    var hooks = 0
    header.methods.forEach { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@forEach
        val sites = instructions.withIndex().mapNotNull { (index, ins) ->
            if (ins.opcode != Opcode.IPUT_OBJECT) return@mapNotNull null
            val field = (ins as ReferenceInstruction).reference as? FieldReference ?: return@mapNotNull null
            if (field.definingClass != header.type || field.type !in placemarkTypes) return@mapNotNull null
            index to (ins as TwoRegisterInstruction).registerA
        }
        // Insert bottom-up so earlier indices stay valid.
        sites.asReversed().forEach { (index, register) ->
            method.addInstruction(
                index + 1,
                "invoke-static/range { v$register .. v$register }, $KAV_EXTENSION->onPlace(Ljava/lang/Object;)V",
            )
            hooks++
        }
    }
    if (hooks == 0) throw PatchException("No place assignments found in ${header.type}")
    kavLogger.info("Hooked $hooks place assignment(s)")
}

private fun BytecodePatchContext.hookActivity() {
    val method = findSuperclassHook(MAPS_ACTIVITY, "Maps activity onCreate (Kav)") {
        it.name == "onCreate" && it.returnType == "V" &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Landroid/os/Bundle;")
    }
    method.addInstruction(
        0,
        "invoke-static/range { p0 .. p0 }, $KAV_EXTENSION->onActivity(Landroid/app/Activity;)V",
    )
}
