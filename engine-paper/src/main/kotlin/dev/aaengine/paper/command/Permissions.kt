package dev.aaengine.paper.command

/** Permissions d'AAEngine, déclarées dans `paper-plugin.yml` (défaut : op, parent `aaengine.admin`). */
object Permissions {
    const val ADMIN: String = "aaengine.admin"
    const val RELOAD: String = "aaengine.command.reload"
    const val GIVE: String = "aaengine.command.give"
    const val ITEMS: String = "aaengine.command.items"
    const val INFO: String = "aaengine.command.info"
    const val PACK: String = "aaengine.command.pack"
    const val VALIDATE: String = "aaengine.command.validate"

    @JvmField
    val ALL_COMMANDS: List<String> = listOf(ADMIN, RELOAD, GIVE, ITEMS, INFO, PACK, VALIDATE)
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
