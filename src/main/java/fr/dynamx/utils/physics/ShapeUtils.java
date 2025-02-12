package fr.dynamx.utils.physics;

import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.bullet.collision.shapes.CompoundCollisionShape;
import com.jme3.bullet.collision.shapes.HullCollisionShape;
import com.jme3.bullet.collision.shapes.infos.ChildCollisionShape;
import com.jme3.bullet.util.DebugShapeFactory;
import com.jme3.math.Vector3f;
import fr.dynamx.api.dxmodel.DxModelPath;
import fr.dynamx.common.DynamXContext;
import fr.dynamx.common.DynamXMain;
import fr.dynamx.common.contentpack.ContentPackLoader;
import fr.dynamx.common.contentpack.PackInfo;
import fr.dynamx.common.objloader.data.DxModelData;
import fr.dynamx.utils.DynamXConstants;
import fr.dynamx.utils.DynamXUtils;
import fr.dynamx.utils.optimization.Vector3fPool;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import vhacd.VHACD;
import vhacd.VHACDParameters;

import java.io.*;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

import static fr.dynamx.common.DynamXMain.log;

@Config(modid = "dynamxmod", category = "vhacd")
@Config.LangKey("dynamx.config.vhacd.title")
@Mod.EventBusSubscriber(modid = "dynamxmod")
public class ShapeUtils {
    private static final byte[] ZIP_BUFFER = new byte[8192];
    private static final Set<String> SAFE_CLASSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            ShapeGenerator.class.getName(), ArrayList.class.getName(), float[].class.getName(), float[].class.getCanonicalName()
    )));

    @Config.Comment("Параметр Convex Hull Downsampling для VHACD")
    @Config.LangKey("dynamx.config.vhacd.convexHullDownSampling")
    @Config.RangeInt(min = 1, max = 10)
    public static int vhacdConvexHullDownSampling = 1;

    @Config.Comment("Параметр Plane Downsampling для VHACD")
    @Config.LangKey("dynamx.config.vhacd.planeDownSampling")
    @Config.RangeInt(min = 1, max = 10)
    public static int vhacdPlaneDownSampling = 1;

    @Config.Comment("Параметр Max Vertices Per Hull для VHACD")
    @Config.LangKey("dynamx.config.vhacd.maxVerticesPerHull")
    @Config.RangeInt(min = 4, max = 1024)
    public static int vhacdMaxVerticesPerHull = 1024;

    @Config.Comment("Параметр Voxel Resolution для VHACD")
    @Config.LangKey("dynamx.config.vhacd.voxelResolution")
    @Config.RangeInt(min = 10000, max = 64000000)
    public static int vhacdVoxelResolution = 10000;


    public static CompoundCollisionShape generateComplexModelCollisions(DxModelPath path, String objectName, Vector3f scale, Vector3f centerOfMass, float shapeYOffset) {
        String lowerCaseObjectName = objectName.toLowerCase();
        String dcFileName = path.getModelPath().toString().replace("." + path.getFormat().toString().toLowerCase(), "_" + lowerCaseObjectName + "_" + DynamXConstants.DC_FILE_VERSION + ".dc");
        ResourceLocation dcFileLocation = new ResourceLocation(dcFileName);
        InputStream dcInputStream = null;
        PackInfo dcFilePackInfo = null;

        for (PackInfo packInfo : path.getPackLocations()) {
            try {
                dcInputStream = packInfo.readFile(dcFileLocation);
                if (dcInputStream != null) {
                    dcFilePackInfo = packInfo;
                    break;
                }
            } catch (IOException e) {
                log.error("Error reading dc file from pack " + packInfo, e);
                throw new RuntimeException("Error accessing dc file: " + dcFileLocation + " in pack: " + packInfo, e);
            }
        }

        ShapeGenerator shapeGenerator = null;
        if (dcInputStream != null) {
            try (InputStream inputStream = dcInputStream) {
                shapeGenerator = loadFile(inputStream);
            } catch (Exception e) {
                log.error("Cannot load .dc file of " + path + ". Re-creating it. Errored dc file was found in " + dcFilePackInfo, e);
            }
        }

        if (shapeGenerator == null) {
            DxModelData model = DynamXContext.getDxModelDataFromCache(path);
            String modelPath = DynamXMain.resourcesDirectory + File.separator + path.getPackName() + File.separator + "assets" +
                    File.separator + path.getModelPath().getNamespace() + File.separator + path.getModelPath().getPath().replace("/", File.separator);
            String dcFilePath = modelPath.replace("." + path.getFormat().toString().toLowerCase(), "_" + lowerCaseObjectName + "_" + DynamXConstants.DC_FILE_VERSION + ".dc");
            File dcFile = new File(dcFilePath);

            float[] pos = lowerCaseObjectName.isEmpty() ? model.getVerticesPos() : model.getVerticesPos(lowerCaseObjectName);
            int[] indices = lowerCaseObjectName.isEmpty() ? model.getAllMeshIndices() : model.getMeshIndices(lowerCaseObjectName);
            if (pos.length == 0 || indices.length == 0) {
                throw new IllegalArgumentException("Part '" + objectName + "' of '" + path + "' does not exist or is empty. Check the name of the part in the obj file.");
            }

            log.info("Converted model '{}' to shape.", path.getModelPath());

            VHACDParameters parameters = new VHACDParameters();
            parameters.setConvexHullDownSampling(vhacdConvexHullDownSampling);
            parameters.setPlaneDownSampling(vhacdPlaneDownSampling);
            parameters.setMaxVerticesPerHull(vhacdMaxVerticesPerHull);
            parameters.setVoxelResolution(vhacdVoxelResolution);

            shapeGenerator = new ShapeGenerator(pos, indices, parameters);

            if (!dcFile.getPath().contains(".zip") && !dcFile.getPath().contains(ContentPackLoader.PACK_FILE_EXTENSION)) {
                dcFile.getParentFile().mkdirs();
                saveFile(dcFile, shapeGenerator);
            } else {
                log.warn("Saving .dc file of '{}' in zipped pack at '{}'. Consider including it in the pack.", path.getModelPath(), dcFile);
                try {
                    File zipFile;
                    if (dcFile.getPath().contains(".zip")) {
                        zipFile = new File(dcFile.getPath().substring(0, dcFile.getPath().lastIndexOf(".zip") + 4));
                    } else {
                        zipFile = new File(dcFile.getPath().substring(0, dcFile.getPath().lastIndexOf(ContentPackLoader.PACK_FILE_EXTENSION) + ContentPackLoader.PACK_FILE_EXTENSION.length()));
                    }
                    addFileToExistingZip(zipFile, dcFile, shapeGenerator);
                    log.info("Saved shape '{}' to zip.", dcFile.getName());
                } catch (IOException e) {
                    log.error("Error adding .dc file to zip archive for '{}'", path.getModelPath(), e);
                    e.printStackTrace();
                }
            }
            log.info("Generated shape for '{}'.", dcFile.getName());
        }
        CompoundCollisionShape collisionShape = new CompoundCollisionShape();
        for (float[] hullPoint : shapeGenerator.getHullPoints()) {
            if (hullPoint.length == 0) {
                throw new IllegalArgumentException("Empty .dc file for part '" + objectName + "' of '" + path + "'. Please delete it, check your obj model and restart the game.");
            }
            HullCollisionShape hullShape = new HullCollisionShape(hullPoint);
            hullShape.setScale(scale);
            collisionShape.addChildShape(hullShape, new Vector3f(centerOfMass.x, shapeYOffset + centerOfMass.y, centerOfMass.z));
        }
        return collisionShape;
    }

    public static void addFileToExistingZip(File zipFile, File modelFile, ShapeGenerator shapeGenerator) throws IOException {
        File outputZipFile = new File(zipFile.getParentFile(), zipFile.getName() + ".temp");
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile.toPath()));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outputZipFile.toPath()))) {

            ZipEntry entry = zin.getNextEntry();
            while (entry != null) {
                String name = entry.getName();
                if (!modelFile.getName().equals(name)) {
                    out.putNextEntry(new ZipEntry(name));
                    long transferred = 0;
                    long entrySize = entry.getSize();
                    while (true) {
                        int len = zin.read(ZIP_BUFFER);
                        if (len <= 0) break;
                        out.write(ZIP_BUFFER, 0, len);
                        transferred += len;
                    }
                }
                entry = zin.getNextEntry();
            }

            out.putNextEntry(new ZipEntry(modelFile.getName()));
            try (ObjectOutputStream shapeBytes = new ObjectOutputStream(new GZIPOutputStream(out))) {
                shapeBytes.writeObject(shapeGenerator);
            }
            out.closeEntry();
        }

        if (!zipFile.delete()) {
            log.warn("Failed to delete old zip file: '{}'", zipFile.getAbsolutePath());
        }
        if (!outputZipFile.renameTo(zipFile)) {
            log.error("Failed to rename temp zip file '{}' to '{}'", outputZipFile.getAbsolutePath(), zipFile.getAbsolutePath());
            throw new IOException("Failed to rename temp zip file: " + outputZipFile.getAbsolutePath() + " to " + zipFile.getAbsolutePath());
        }
        log.info("Updated zip file: '{}'", zipFile.getAbsolutePath());
    }

    private static void saveFile(File file, ShapeGenerator shapeGenerator) {
        try (ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(Files.newOutputStream(file.toPath())))) {
            out.writeObject(shapeGenerator);
            log.info("Saved shape file: '{}'", file.getName());
        } catch (IOException e) {
            log.error("Error saving shape file: '{}'", file.getName(), e);
            e.printStackTrace();
        }
    }

    private static ShapeGenerator loadFile(InputStream file) {
        try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(file)) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                if (!SAFE_CLASSES.contains(desc.getName()) && !SAFE_CLASSES.contains(desc.forClass().getCanonicalName())) {
                    throw new InvalidClassException("Unauthorized deserialization attempt", desc.getName());
                }
                return super.resolveClass(desc);
            }
        }) {
            return (ShapeGenerator) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Cannot load shape file", e);
        }
    }

    public static FloatBuffer[] getDebugBuffer(CompoundCollisionShape compoundShape) {
        int childrenCount = compoundShape.listChildren().length;
        FloatBuffer[] debugBuffer = new FloatBuffer[childrenCount];
        int i = 0;
        for (ChildCollisionShape ccs : compoundShape.listChildren()) {
            debugBuffer[i++] = getDebugBuffer(ccs.getShape());
        }
        return debugBuffer;
    }

    public static FloatBuffer getDebugBuffer(CollisionShape collisionShape) {
        return DebugShapeFactory.getDebugTriangles(collisionShape, DebugShapeFactory.highResolution);
    }

    public static List<Vector3f> getDebugVectorList(FloatBuffer[] debugBuffer) {
        return getDebugVectorList(null, debugBuffer);
    }

    public static List<Vector3f> getDebugVectorList(CompoundCollisionShape compoundShape, FloatBuffer[] debugBuffer) {
        Vector3fPool.openPool();
        List<Vector3f> vectors = new ArrayList<>();
        if (compoundShape != null) {
            int j = 0;
            for (ChildCollisionShape sh : compoundShape.listChildren()) {
                FloatBuffer fb = debugBuffer[j++];
                if (fb != null) {
                    vectors.addAll(DynamXUtils.floatBufferToVec3f(fb, sh.copyOffset(Vector3fPool.get())));
                }
            }
        } else {
            for (FloatBuffer fb : debugBuffer) {
                if (fb != null) {
                    vectors.addAll(DynamXUtils.floatBufferToVec3f(fb, Vector3fPool.get()));
                }
            }
        }
        Vector3fPool.closePool();
        return vectors;
    }

    public static class ShapeGenerator implements Serializable {
        private static final long serialVersionUID = 1L;
        public final List<float[]> points = new ArrayList<>();

        public ShapeGenerator(float[] positions, int[] indices, VHACDParameters params) {
            VHACD.compute(positions, indices, params).forEach(hull -> points.add(hull.clonePositions()));
        }

        public List<float[]> getHullPoints() {
            return points;
        }
    }

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals("dynamxmod")) {
            ConfigManager.sync("dynamxmod", Config.Type.INSTANCE);
        }
    }
}
