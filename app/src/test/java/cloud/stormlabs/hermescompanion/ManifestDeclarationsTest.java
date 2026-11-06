package cloud.stormlabs.hermescompanion;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Guards the crash class "ActivityNotFoundException: Unable to find explicit activity class
 * ... have you declared this activity in your AndroidManifest.xml".
 *
 * Two rules, both derived from the real sources so they cannot drift:
 *  1. every class in this package that extends Activity/Service must be declared in the manifest;
 *  2. every local class used as an explicit {@code new Intent(ctx, X.class)} target must be declared.
 */
public class ManifestDeclarationsTest {

    private static Path moduleDir() {
        for (String candidate : new String[]{"src/main", "app/src/main"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) return p;
        }
        throw new IllegalStateException("run from the app module or repo root");
    }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static List<Path> javaSources() throws Exception {
        Path src = moduleDir().resolve("java/cloud/stormlabs/hermescompanion");
        try (Stream<Path> s = Files.walk(src)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /** All component class names declared in the manifest, base names only. */
    private static List<String> declaredComponents() throws Exception {
        String manifest = read(moduleDir().resolve("AndroidManifest.xml"));
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("android:name=\"\\.([A-Za-z0-9_]+)\"").matcher(manifest);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test public void everyActivityAndServiceIsDeclared() throws Exception {
        List<String> declared = declaredComponents();
        List<String> missing = new ArrayList<>();
        for (Path p : javaSources()) {
            String body = read(p);
            boolean component = body.contains("extends Activity")
                    || body.contains("extends Service")
                    || body.contains("extends TileService")
                    || body.contains("extends VoiceInteractionService");
            if (!component) continue;
            if (body.contains("abstract class")) continue;
            String name = p.getFileName().toString().replace(".java", "");
            if (!declared.contains(name)) missing.add(name);
        }
        if (!missing.isEmpty()) fail("extends Activity/Service but not declared in manifest: " + missing);
    }

    @Test public void everyExplicitIntentTargetIsDeclared() throws Exception {
        List<Path> sources = javaSources();
        List<String> localClasses = sources.stream()
                .map(p -> p.getFileName().toString().replace(".java", ""))
                .collect(Collectors.toList());
        List<String> declared = declaredComponents();

        Pattern intent = Pattern.compile("new\\s+Intent\\s*\\([^,]+,\\s*([A-Za-z0-9_]+)\\.class\\s*\\)");
        List<String> undeclared = new ArrayList<>();
        for (Path p : sources) {
            // A class that only extends Activity IS itself a component target for the launcher;
            // still, the interesting case is one class starting another.
            Matcher m = intent.matcher(read(p));
            while (m.find()) {
                String target = m.group(1);
                if (!localClasses.contains(target)) continue;      // external / framework class
                if ("MainActivity".equals(target)) continue;        // launcher entry, checked by the other test
                if (!declared.contains(target)
                        && undeclared.stream().noneMatch(s -> s.startsWith(target))) {
                    undeclared.add(target + " (from " + p.getFileName() + ")");
                }
            }
        }
        if (!undeclared.isEmpty()) fail("explicit Intent target missing from manifest: " + undeclared);
    }

    @Test public void manifestStillDeclaresTheLaunchableEntryPoint() throws Exception {
        String manifest = read(moduleDir().resolve("AndroidManifest.xml"));
        assertTrue(".MainActivity must stay exported with a LAUNCHER filter",
                manifest.contains(".MainActivity")
                        && manifest.contains("android.intent.category.LAUNCHER"));
    }

    @Test public void packageDeclarationMatchesNamespace() throws Exception {
        // A wrong package would make every android:name=".X" resolution fail at runtime.
        File f = moduleDir().resolve("java/cloud/stormlabs/hermescompanion/MainActivity.java").toFile();
        assertTrue("namespace and source package must agree",
                read(f.toPath()).toLowerCase(Locale.ROOT)
                        .contains("package cloud.stormlabs.hermescompanion;"));
    }
}
