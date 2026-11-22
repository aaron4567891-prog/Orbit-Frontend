package au.orbit.frontend

import android.content.Context
import android.graphics.Color

/** The panel down the left of the Systems screen, behind the wheel. Chosen in System Settings → Customisation. */
internal object SystemsPanel {
    enum class Style(val label: String) { GLASS("Glass"), SOLID("Solid"), OFF("Off") }
    enum class Width(val label: String, val fraction: Float) { NARROW("Narrow", 0.26f), MEDIUM("Medium", 0.32f), WIDE("Wide", 0.40f) }
    /** Width in pixels of the softened copy of the hero shown through the glass: fewer pixels, more blur. */
    enum class Blur(val label: String, val pixels: Int) { CLEAR("Clear", 224), SOFT("Soft", 96), FROSTED("Frosted", 48) }
    /** How strongly the panel colour tints the glass (0–255). */
    enum class Tint(val label: String, val alpha: Int) { LIGHT("Light", 30), MEDIUM("Medium", 80), STRONG("Strong", 140), NONE("None", 0) }
    /** Panel colour; null follows the style (white frost for glass, grey for solid) or the theme accent. */
    enum class Colour(val label: String, val rgb: Int?) {
        AUTO("Auto", null), GREY("Grey", 0xFF5F646B.toInt()), CHARCOAL("Charcoal", 0xFF1F2329.toInt()),
        WHITE("White", 0xFFF2F3F5.toInt()), NAVY("Navy", 0xFF102C4B.toInt()), BLUE("Blue", 0xFF1679DA.toInt()),
        RED("Red", 0xFFC62828.toInt()), PURPLE("Purple", 0xFF5E35B1.toInt()), GREEN("Green", 0xFF2E7D32.toInt()),
        ACCENT("Theme accent", null)
    }
    enum class BorderColour(val label: String, val rgb: Int?) {
        AUTO("Auto", null), WHITE("White", Color.WHITE), BLACK("Black", Color.BLACK),
        GREY("Grey", 0xFFC9CCD1.toInt()), ACCENT("Theme accent", null)
    }

    /** Colour of the logos on the panel; Auto turns grey and black single-colour logos white. */
    enum class LogoColour(val label: String, val rgb: Int?) {
        AUTO("Auto", null), ORIGINAL("Original", null), WHITE("White", Color.WHITE),
        BLACK("Black", Color.BLACK), ACCENT("Theme accent", null)
    }

    private const val STYLE_KEY = "systemsPanel.style"
    private const val LOGO_COLOUR_KEY = "systemsPanel.logoColour"
    private const val WIDTH_KEY = "systemsPanel.width"
    private const val BLUR_KEY = "systemsPanel.blur"
    private const val TINT_KEY = "systemsPanel.tint"
    private const val COLOUR_KEY = "systemsPanel.colour"
    private const val BORDER_KEY = "systemsPanel.border"
    private const val BORDER_COLOUR_KEY = "systemsPanel.borderColour"

    fun style() = Style.entries.getOrElse(UserDataStore.cachedInt(STYLE_KEY, 0)) { Style.GLASS }
    fun width() = Width.entries.getOrElse(UserDataStore.cachedInt(WIDTH_KEY, Width.MEDIUM.ordinal)) { Width.MEDIUM }
    fun blur() = Blur.entries.getOrElse(UserDataStore.cachedInt(BLUR_KEY, 0)) { Blur.CLEAR }
    fun tint() = Tint.entries.getOrElse(UserDataStore.cachedInt(TINT_KEY, 0)) { Tint.LIGHT }
    fun colour() = Colour.entries.getOrElse(UserDataStore.cachedInt(COLOUR_KEY, 0)) { Colour.AUTO }
    fun border() = UserDataStore.cachedInt(BORDER_KEY, 1) != 0
    fun logoColour() = LogoColour.entries.getOrElse(UserDataStore.cachedInt(LOGO_COLOUR_KEY, 0)) { LogoColour.AUTO }
    fun borderColour() = BorderColour.entries.getOrElse(UserDataStore.cachedInt(BORDER_COLOUR_KEY, 0)) { BorderColour.AUTO }

    /** The opaque panel colour for [style]. */
    fun fillColor(style: Style, theme: OrbitThemes.Theme): Int = when (val colour = colour()) {
        Colour.AUTO -> if (style == Style.GLASS) Color.WHITE else LogoHalo.WINDOW_FILL
        Colour.ACCENT -> theme.accent
        else -> colour.rgb!!
    }
    /** The glass tint: the panel colour at the chosen strength. */
    fun glassTint(theme: OrbitThemes.Theme): Int {
        val color = fillColor(Style.GLASS, theme)
        return Color.argb(tint().alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
    fun borderColor(style: Style, theme: OrbitThemes.Theme): Int = when (val colour = borderColour()) {
        BorderColour.AUTO -> if (style == Style.GLASS) Color.argb(150, 255, 255, 255) else LogoHalo.WINDOW_BORDER
        BorderColour.ACCENT -> theme.accent
        else -> colour.rgb!!
    }

    /** Share of the screen heroes keep their logo clear of: the panel when shown, else the logo column. */
    fun inset() = if (style() == Style.OFF) 0.27f else width().fraction

    private fun <T : Enum<T>> next(entries: List<T>, current: T) = (current.ordinal + 1) % entries.size
    fun cycleStyle(context: Context) = UserDataStore.put(context, "settings", STYLE_KEY, next(Style.entries, style()))
    fun cycleWidth(context: Context) = UserDataStore.put(context, "settings", WIDTH_KEY, next(Width.entries, width()))
    fun cycleBlur(context: Context) = UserDataStore.put(context, "settings", BLUR_KEY, next(Blur.entries, blur()))
    fun cycleTint(context: Context) = UserDataStore.put(context, "settings", TINT_KEY, next(Tint.entries, tint()))
    fun cycleColour(context: Context) = UserDataStore.put(context, "settings", COLOUR_KEY, next(Colour.entries, colour()))
    fun toggleBorder(context: Context) = UserDataStore.put(context, "settings", BORDER_KEY, if (border()) 0 else 1)
    fun cycleLogoColour(context: Context) =
        UserDataStore.put(context, "settings", LOGO_COLOUR_KEY, next(LogoColour.entries, logoColour()))
    fun cycleBorderColour(context: Context) =
        UserDataStore.put(context, "settings", BORDER_COLOUR_KEY, next(BorderColour.entries, borderColour()))

    /** Per-system logo colour, chosen from the Y menu on the Systems screen; Default follows Logo colour above. */
    enum class SystemLogoColour(val label: String, val rgb: Int?) {
        DEFAULT("Default", null), ORIGINAL("Original", null), WHITE("White", Color.WHITE), BLACK("Black", Color.BLACK),
        ACCENT("Theme accent", null), RED("Red", 0xFFE53935.toInt()), BLUE("Blue", 0xFF1E88E5.toInt()),
        GREEN("Green", 0xFF43A047.toInt()), YELLOW("Yellow", 0xFFFDD835.toInt()), PURPLE("Purple", 0xFF8E24AA.toInt())
    }
    /** Size of the system logos, or names when a system has no logo, for every system. */
    enum class TextSize(val label: String, val scale: Float) {
        SMALL("Small", 0.75f), NORMAL("Normal", 1f), LARGE("Large", 1.4f), EXTRA_LARGE("Extra large", 1.8f)
    }
    private fun systemKey(prefix: String, systemId: String) = "$prefix.$systemId"
    fun systemLogoColour(systemId: String) =
        SystemLogoColour.entries.getOrElse(UserDataStore.cachedInt(systemKey("systemLogoColour", systemId), 0)) { SystemLogoColour.DEFAULT }
    private const val TEXT_SIZE_KEY = "systemsPanel.textSize"
    /** Text size for every system, set on the Systems panel page. */
    fun textSize() = TextSize.entries.getOrElse(UserDataStore.cachedInt(TEXT_SIZE_KEY, TextSize.NORMAL.ordinal)) { TextSize.NORMAL }
    fun cycleTextSize(context: Context) = UserDataStore.put(context, "settings", TEXT_SIZE_KEY, next(TextSize.entries, textSize()))
    /** Text size applies to every system alike. */
    fun textSize(@Suppress("UNUSED_PARAMETER") systemId: String) = textSize()
    fun cycleSystemLogoColour(context: Context, systemId: String) = UserDataStore.put(context, "settings",
        systemKey("systemLogoColour", systemId), next(SystemLogoColour.entries, systemLogoColour(systemId)))

    /** Per-system colour of the frame around the selected logo (its border and soft fill); Default is the theme accent. */
    enum class SelectionColour(val label: String, val rgb: Int?) {
        DEFAULT("Default", null), WHITE("White", Color.WHITE), BLACK("Black", Color.BLACK), GREY("Grey", 0xFF9EA3AA.toInt()),
        RED("Red", 0xFFE53935.toInt()), ORANGE("Orange", 0xFFFB8C00.toInt()), YELLOW("Yellow", 0xFFFDD835.toInt()),
        GREEN("Green", 0xFF43A047.toInt()), BLUE("Blue", 0xFF1E88E5.toInt()), PURPLE("Purple", 0xFF8E24AA.toInt())
    }
    fun selectionColour(systemId: String) =
        SelectionColour.entries.getOrElse(UserDataStore.cachedInt(systemKey("systemSelectionColour", systemId), 0)) { SelectionColour.DEFAULT }
    fun cycleSelectionColour(context: Context, systemId: String) = UserDataStore.put(context, "settings",
        systemKey("systemSelectionColour", systemId), next(SelectionColour.entries, selectionColour(systemId)))
    /** The selection frame's colour for [systemId]: its own choice, else the theme accent. */
    fun selectionColor(systemId: String, theme: OrbitThemes.Theme) = selectionColour(systemId).rgb ?: theme.accent
}
