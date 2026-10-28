package dev.aaengine.paper.command

/** Permissions d'AAEngine, déclarées dans `paper-plugin.yml` (défaut : op, parent `aaengine.admin`). */
object Permissions {
    const val ADMIN: String = "aaengine.admin"
    const val RELOAD: String = "aaengine.command.reload"
    const val GIVE: String = "aaengine.command.give"
    const val ITEMS: String = "aaengine.command.items"
    const val INFO: String = "aaengine.command.info"

    @JvmField
    val ALL_COMMANDS: List<String> = listOf(ADMIN, RELOAD, GIVE, ITEMS, INFO)
}

// Developped by AzurHOSTS.com and powered by AzurWare.fr.
