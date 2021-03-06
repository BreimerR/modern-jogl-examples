package glNext.tut14

import com.jogamp.newt.event.KeyEvent
import com.jogamp.newt.event.MouseEvent
import com.jogamp.opengl.GL2ES2.GL_RED
import com.jogamp.opengl.GL2ES3.*
import com.jogamp.opengl.GL2GL3.GL_TEXTURE_1D
import com.jogamp.opengl.GL3
import com.jogamp.opengl.GL3.GL_DEPTH_CLAMP
import com.jogamp.opengl.util.texture.spi.DDSImage
import glNext.*
import glm.*
import glm.vec._3.Vec3
import glm.vec._4.Vec4
import glm.quat.Quat
import glm.mat.Mat4
import main.framework.Framework
import main.framework.Semantic
import main.framework.component.Mesh
import uno.buffer.*
import uno.gl.UniformBlockArray
import uno.glm.MatrixStack
import uno.glsl.programOf
import uno.mousePole.*
import uno.time.Timer
import java.io.File
import java.nio.ByteBuffer

/**
 * Created by elect on 29/03/17.
 */

fun main(args: Array<String>) {
    MaterialTexture_Next().setup("Tutorial 14 - Material Texture")
}

class MaterialTexture_Next : Framework() {

    lateinit var programs: Array<ProgramData>
    lateinit var unlit: UnlitProgData

    val initialObjectData = ObjectData(
            Vec3(0.0f, 0.5f, 0.0f),
            Quat(1.0f, 0.0f, 0.0f, 0.0f))
    val initialViewData = ViewData(
            Vec3(initialObjectData.position),
            Quat(0.92387953f, 0.3826834f, 0.0f, 0.0f),
            10.0f,
            0.0f)
    val viewScale = ViewScale(
            1.5f, 70.0f,
            1.5f, 0.5f,
            0.0f, 0.0f, //No camera movement.
            90.0f / 250.0f)
    val viewPole = ViewPole(initialViewData, viewScale, MouseEvent.BUTTON1)
    val objectPole = ObjectPole(initialObjectData, 90.0f / 250.0f, MouseEvent.BUTTON3, viewPole)

    val shaderPairs = arrayOf(Pair("pn.vert", "fixed-shininess.frag"), Pair("pnt.vert", "texture-shininess.frag"), Pair("pnt.vert", "texture-compute.frag"))

    lateinit var objectMesh: Mesh
    lateinit var cube: Mesh
    lateinit var plane: Mesh

    object Buffer {
        val PROJECTION = 0
        val LIGHT = 1
        val MATERIAL = 2
        val MAX = 3
    }

    object Texture {
        val SHINE = NUM_GAUSSIAN_TEXTURES
        val MAX = SHINE + 1
    }

    val bufferName = intBufferBig(Buffer.MAX)
    val textureName = intBufferBig(Texture.MAX)
    val samplerName = intBufferBig(1)

    var materialOffset = 0
    var currMaterial = 0
    var currTexture = NUM_GAUSSIAN_TEXTURES - 1

    val lightTimer = Timer(Timer.Type.Loop, 6.0f)

    val halfLightDistance = 25.0f
    val lightAttenuation = 1.0f / (halfLightDistance * halfLightDistance)
    val lightHeight = 1.0f
    val lightRadius = 3.0f

    var mode = ShaderMode.FIXED

    var drawLights = true
    var drawCameraPos = false
    var useInfinity = true

    override fun init(gl: GL3) = with(gl) {

        initializePrograms(gl)

        objectMesh = Mesh(gl, javaClass, "tut14/Infinity.xml")
        cube = Mesh(gl, javaClass, "tut14/UnitCube.xml")
        plane = Mesh(gl, javaClass, "tut14/UnitPlane.xml")

        val depthZNear = 0.0f
        val depthZFar = 1.0f

        cullFace {
            enable()
            cullFace = back
            frontFace = cw
        }
        depth {
            test = true
            mask = true
            func = lEqual
            rangef = depthZNear..depthZFar
            clamp = true
        }

        glGenBuffers(Buffer.MAX, bufferName)

        //Setup our Uniform Buffers
        setupMaterials(gl)

        withUniformBuffer(bufferName[Buffer.LIGHT]) {

            data(LightBlock.SIZE, GL_DYNAMIC_DRAW)
            range(Semantic.Uniform.LIGHT, 0, LightBlock.SIZE)

            name = bufferName[Buffer.PROJECTION]
            data(Mat4.SIZE, GL_DYNAMIC_DRAW)
            range(Semantic.Uniform.PROJECTION, 0, Mat4.SIZE)

            name = bufferName[Buffer.MATERIAL]
            range(Semantic.Uniform.MATERIAL, 0, MaterialBlock.SIZE)
        }

        glBindBuffer(GL_UNIFORM_BUFFER)

        glGenTextures(textureName)
        createGaussianTextures(gl)
        createShininessTexture(gl)
    }

    fun initializePrograms(gl: GL3) {

        programs = Array(ShaderMode.MAX, { ProgramData(gl, shaderPairs[it]) })

        unlit = UnlitProgData(gl, "unlit")
    }

    fun setupMaterials(gl: GL3) {

        val mtls = UniformBlockArray(gl, MaterialBlock.SIZE, NUM_MATERIALS)

        val mtl = MaterialBlock
        mtl.diffuseColor = Vec4(1.0f, 0.673f, 0.043f, 1.0f)
        mtl.specularColor = Vec4(1.0f, 0.673f, 0.043f, 1.0f).times(0.4f)
        mtl.specularShininess = 0.125f
        mtls[0] = mtl.toBuffer()

        mtl.diffuseColor = Vec4(0.01f, 0.01f, 0.01f, 1.0f)
        mtl.specularColor = Vec4(0.99f, 0.99f, 0.99f, 1.0f)
        mtl.specularShininess = 0.125f
        mtls[1] = mtl.toBuffer()

        mtls.uploadBufferObject(gl, bufferName[Buffer.MATERIAL])
        materialOffset = mtls.arrayOffset

        mtls.dispose()
    }

    fun createGaussianTextures(gl: GL3) = with(gl) {

        repeat(NUM_GAUSSIAN_TEXTURES) {
            val cosAngleResolution = calcCosAngleResolution(it)
            createGaussianTexture(gl, it, cosAngleResolution, 128)
        }
        initSampler(samplerName) {
            magFilter = nearest
            minFilter = nearest
            wrapS = clampToEdge
            wrapT = clampToEdge
        }
    }

    fun calcCosAngleResolution(level: Int): Int {
        val cosAngleStart = 64
        return cosAngleStart * glm.pow(2f, level.toFloat()).toInt()
    }

    fun createGaussianTexture(gl: GL3, index: Int, cosAngleResolution: Int, shininessResolution: Int) = with(gl) {

        val textureData = buildGaussianData(cosAngleResolution, shininessResolution)

        withTexture1d(textureName[index]) {
            image(GL_R8, cosAngleResolution, GL_RED, GL_UNSIGNED_BYTE, textureData)
            levels = 0..0
        }
        textureData.destroy()
    }

    fun buildGaussianData(cosAngleResolution: Int, shininessResolution: Int): ByteBuffer {

        val textureData = byteBufferBig(cosAngleResolution * shininessResolution)

        repeat(shininessResolution) { iShin ->

            val shininess = iShin / shininessResolution.f

            repeat(cosAngleResolution) { iCosAng ->

                val cosAng = iCosAng / (cosAngleResolution - 1).f
                val angle = glm.acos(cosAng)
                var exponent = angle / shininess
                exponent = -(exponent * exponent)
                val gaussianTerm = glm.exp(exponent)

                textureData[iCosAng] = (gaussianTerm * 255f).b
            }
        }
        return textureData
    }

    fun createShininessTexture(gl: GL3) = with(gl) {

        val file = File(javaClass.getResource("/tut14/main.dds").toURI())

        val image = DDSImage.read(file)

        withTexture2d(textureName[Texture.SHINE]) {
            image(GL_R8, image.width, image.height, GL_RED, GL_UNSIGNED_BYTE, image.getMipMap(0).data)
            levels = 0..0
        }
    }

    override fun display(gl: GL3) = with(gl) {

        lightTimer.update()

        clear {
            color(0.75f, 0.75f, 1.0f, 1.0f)
            depth()
        }

        val modelMatrix = MatrixStack(viewPole.calcMatrix())
        val worldToCamMat = modelMatrix.top()

        val lightData = LightBlock

        lightData.ambientIntensity = Vec4(0.2f, 0.2f, 0.2f, 1.0f)
        lightData.lightAttenuation = lightAttenuation

        val globalLightDirection = Vec3(0.707f, 0.707f, 0.0f)

        lightData.lights[0].cameraSpaceLightPos = worldToCamMat * Vec4(globalLightDirection, 0.0f)
        lightData.lights[0].lightIntensity = Vec4(0.6f, 0.6f, 0.6f, 1.0f)

        lightData.lights[1].cameraSpaceLightPos = worldToCamMat * calcLightPosition()
        lightData.lights[1].lightIntensity = Vec4(0.4f, 0.4f, 0.4f, 1.0f)

        withUniformBuffer(bufferName[Buffer.LIGHT]) { subData(lightData.toBuffer()) }

        run {

            val mesh = if (useInfinity) objectMesh else plane

            glBindBufferRange(GL_UNIFORM_BUFFER, Semantic.Uniform.MATERIAL, bufferName[Buffer.MATERIAL],
                    currMaterial * materialOffset.L, MaterialBlock.SIZE.L)

            modelMatrix run {

                applyMatrix(objectPole.calcMatrix())
                scale(if (useInfinity) 2.0f else 4.0f)

                val normMatrix = modelMatrix.top().toMat3()
                normMatrix.inverse_().transpose_()

                val prog = programs[mode]

                usingProgram(prog.theProgram) {

                    prog.modelToCameraMatrixUnif.mat4 = top()
                    prog.normalModelToCameraMatrixUnif.mat3 = normMatrix

                    withTexture2d(Semantic.Sampler.GAUSSIAN_TEXTURE, textureName[currTexture], samplerName) {

                        withTexture2d(Semantic.Sampler.SHININESS_TEXTURE, textureName[Texture.SHINE], samplerName) {

                            if (mode != ShaderMode.FIXED)
                                mesh.render(gl, "lit-tex")
                            else
                                mesh.render(gl, "lit")
                        }
                    }
                }
                glBindBufferBase(GL_UNIFORM_BUFFER, Semantic.Uniform.MATERIAL)
            }
        }

        if (drawLights)

            modelMatrix run {

                translate(calcLightPosition())
                scale(0.25f)

                usingProgram(unlit.theProgram) {

                    unlit.modelToCameraMatrixUnif.mat4 = top()

                    val lightColor = Vec4(1.0f)
                    unlit.objectColorUnif.vec4 = lightColor
                    cube.render(gl, "flat")

                    reset()
                    translate(globalLightDirection * 100.0f)
                    scale(5.0f)

                    unlit.modelToCameraMatrixUnif.mat4 = top()
                    cube.render(gl, "flat")
                }
            }

        if (drawCameraPos)

            modelMatrix run {

                setIdentity()
                translate(Vec3(0.0f, 0.0f, -viewPole.getView().radius))
                scale(0.25f)

                depth {
                    test = false
                    mask = false
                }
                usingProgram(unlit.theProgram) {
                    unlit.modelToCameraMatrixUnif.mat4 = top()
                    glUniform4f(unlit.objectColorUnif, 0.25f, 0.25f, 0.25f, 1.0f)
                    cube.render(gl, "flat")

                    depth {
                        mask = true
                        test = true
                    }
                    glUniform4f(unlit.objectColorUnif, 1.0f)
                    cube.render(gl, "flat")
                }
            }
    }

    fun calcLightPosition(): Vec4 {

        val scale = glm.PIf * 2.0f

        val timeThroughLoop = lightTimer.getAlpha()
        val ret = Vec4(0.0f, lightHeight, 0.0f, 1.0f)

        ret.x = glm.cos(timeThroughLoop * scale) * lightRadius
        ret.z = glm.sin(timeThroughLoop * scale) * lightRadius

        return ret
    }

    override fun reshape(gl: GL3, w: Int, h: Int) = with(gl) {

        val zNear = 1.0f
        val zFar = 1_000f
        val perspMatrix = MatrixStack()

        val proj = perspMatrix.perspective(45.0f, w.f / h, zNear, zFar).top()

        withUniformBuffer(bufferName[Buffer.PROJECTION]) { subData(proj) }

        glViewport(w, h)
    }

    override fun mousePressed(e: MouseEvent) {
        viewPole.mousePressed(e)
        objectPole.mousePressed(e)
    }

    override fun mouseDragged(e: MouseEvent) {
        viewPole.mouseDragged(e)
        objectPole.mouseDragged(e)
    }

    override fun mouseReleased(e: MouseEvent) {
        viewPole.mouseReleased(e)
        objectPole.mouseReleased(e)
    }

    override fun mouseWheelMoved(e: MouseEvent) {
        viewPole.mouseWheel(e)
    }

    override fun keyPressed(e: KeyEvent) {

        when (e.keyCode) {

            KeyEvent.VK_ESCAPE -> quit()

            KeyEvent.VK_P -> lightTimer.togglePause()
            KeyEvent.VK_MINUS -> lightTimer.rewind(0.5f)
            KeyEvent.VK_PLUS -> lightTimer.fastForward(0.5f)
            KeyEvent.VK_T -> drawCameraPos = !drawCameraPos
            KeyEvent.VK_G -> drawLights = !drawLights
            KeyEvent.VK_Y -> useInfinity = !useInfinity

            KeyEvent.VK_SPACE -> {
                mode = (mode + 1) % ShaderMode.MAX
                println(shaderModeNames[mode])
            }
        }

        if (e.keyCode in KeyEvent.VK_1..KeyEvent.VK_9) {
            var number = e.keyCode - KeyEvent.VK_1
            if (number < NUM_GAUSSIAN_TEXTURES) {
                println("Angle Resolution: " + calcCosAngleResolution(number))
                currTexture = number
            }
            if (number >= 9 - NUM_MATERIALS) {
                number -= 9 - NUM_MATERIALS
                println("Material Number: " + number)
                currMaterial = number
            }
        }

        viewPole.keyPressed(e)
    }

    val shaderModeNames = arrayOf("Fixed Shininess with Gaussian Texture", "Texture Shininess with Gaussian Texture", "Texture Shininess with computed Gaussian")

    override fun end(gl: GL3) = with(gl) {

        repeat(ShaderMode.MAX) { glDeleteProgram(programs[it].theProgram) }
        glDeleteProgram(unlit.theProgram)

        glDeleteBuffers(bufferName)
        glDeleteSampler(samplerName)
        glDeleteTextures(textureName)

        objectMesh.dispose(gl)
        cube.dispose(gl)

        destroyBuffers(bufferName, samplerName, textureName, LightBlock.buffer, MaterialBlock.buffer)
    }

    object ShaderMode {
        val FIXED = 0
        val TEXTURED = 1
        val TEXTURED_COMPUTE = 2
        val MAX = 3
    }

    class PerLight {

        lateinit var cameraSpaceLightPos: Vec4
        lateinit var lightIntensity: Vec4

        fun to(buffer: ByteBuffer, offset: Int): ByteBuffer {
            cameraSpaceLightPos.to(buffer, offset)
            return lightIntensity.to(buffer, offset + Vec4.SIZE)
        }

        companion object {
            val SIZE = Vec4.SIZE * 2
        }
    }

    object LightBlock {

        lateinit var ambientIntensity: Vec4
        var lightAttenuation = 0f
        var padding = FloatArray(3)
        var lights = arrayOf(PerLight(), PerLight())

        fun toBuffer(): ByteBuffer {
            ambientIntensity to buffer
            buffer.putFloat(Vec4.SIZE, lightAttenuation)
            repeat(NUMBER_OF_LIGHTS) { lights[it].to(buffer, 2 * Vec4.SIZE + it * PerLight.SIZE) }
            return buffer
        }

        val SIZE = Vec4.SIZE * 2 + NUMBER_OF_LIGHTS * PerLight.SIZE
        val buffer = byteBufferBig(SIZE)
    }

    object MaterialBlock {

        lateinit var diffuseColor: Vec4
        lateinit var specularColor: Vec4
        var specularShininess = 0f
        var padding = FloatArray(3)

        fun toBuffer(): ByteBuffer {
            diffuseColor to buffer
            specularColor.to(buffer, Vec4.SIZE)
            return buffer.putFloat(2 * Vec4.SIZE, specularShininess)
        }

        val SIZE = 3 * Vec4.SIZE
        val buffer = byteBufferBig(SIZE)
    }

    class ProgramData(gl: GL3, shaderPair: Pair<String, String>) {

        val theProgram = programOf(gl, javaClass, "tut14", shaderPair.first, shaderPair.second)

        val modelToCameraMatrixUnif = gl.glGetUniformLocation(theProgram, "modelToCameraMatrix")
        val normalModelToCameraMatrixUnif = gl.glGetUniformLocation(theProgram, "normalModelToCameraMatrix")

        init {
            with(gl) {
                glUniformBlockBinding(
                        theProgram,
                        glGetUniformBlockIndex(theProgram, "Projection"),
                        Semantic.Uniform.PROJECTION)
                glUniformBlockBinding(
                        theProgram,
                        glGetUniformBlockIndex(theProgram, "Material"),
                        Semantic.Uniform.MATERIAL)
                glUniformBlockBinding(
                        theProgram,
                        glGetUniformBlockIndex(theProgram, "Light"),
                        Semantic.Uniform.LIGHT)

                glUseProgram(theProgram)
                glUniform1i(
                        glGetUniformLocation(theProgram, "gaussianTexture"),
                        Semantic.Sampler.GAUSSIAN_TEXTURE)
                glUniform1i(
                        glGetUniformLocation(theProgram, "shininessTexture"),
                        Semantic.Sampler.SHININESS_TEXTURE)
                glUseProgram(theProgram)
            }
        }
    }

    class UnlitProgData(gl: GL3, shader: String) {

        val theProgram = programOf(gl, javaClass, "tut14", shader + ".vert", shader + ".frag")

        val objectColorUnif = gl.glGetUniformLocation(theProgram, "objectColor")
        val modelToCameraMatrixUnif = gl.glGetUniformLocation(theProgram, "modelToCameraMatrix")

        init {
            gl.glUniformBlockBinding(
                    theProgram,
                    gl.glGetUniformBlockIndex(theProgram, "Projection"),
                    Semantic.Uniform.PROJECTION)
        }
    }

    companion object {
        val NUMBER_OF_LIGHTS = 2
        val NUM_MATERIALS = 2
        val NUM_GAUSSIAN_TEXTURES = 4
    }
}