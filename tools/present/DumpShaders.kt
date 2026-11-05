import com.fractal.deepzoom.*
import java.io.File
// Writes the shaders and a preset ramp for present_test.c. See run.sh.
fun main() {
    File("vert.glsl").writeText(Shaders.VERTEX)
    File("direct.glsl").writeText(Shaders.DIRECT)
    File("blit.glsl").writeText(Shaders.BLIT)
    File("ramp.bin").writeBytes(Palettes.buildRamp(Palettes.PRESETS[0]))
}
