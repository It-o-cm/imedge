package com.intermarche.edge.security;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the reach of the generalized access control (BO-01-04-01): every
 * back-office screen handler — under {@code /admin} or {@code /dashboard} —
 * declares the feature and the option it exercises, and none is left on a
 * fixed role. The only handlers without declaration are the account's own
 * doors: sign-in, sign-out, password, single sign-on, favorites and the home
 * page that redirects to the first screen allowed.
 */
class FeatureAccessCoverageTest {

    /** The handlers that serve the signed-in account itself, not a feature. */
    private static final Set<String> SELF_SERVICE = Set.of(
            "AdminAuthResource.loginPage", "AdminAuthResource.logout", "AdminAuthResource.passwordPage",
            "AdminAuthResource.changePassword", "AdminSettingsResource.adminRoot",
            "AdminFavoriteResource.favoritesPage", "AdminFavoriteResource.save", "SsoLoginResource.login");

    /**
     * Lists the classes of a package, walking the compiled classes directory.
     *
     * @param pkg the package
     * @return the classes found
     * @throws IOException when the directory cannot be walked
     * @throws URISyntaxException never for a file URL
     * @throws ClassNotFoundException never for a compiled class
     */
    private static List<Class<?>> classes(String pkg) throws IOException, URISyntaxException, ClassNotFoundException {
        List<Class<?>> found = new ArrayList<>();
        java.util.Enumeration<URL> roots = FeatureAccessCoverageTest.class.getClassLoader()
                .getResources(pkg.replace('.', '/'));
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            if (root.toString().contains("test-classes")) {
                continue;
            }
            java.nio.file.Path base = Paths.get(root.toURI());
            try (Stream<java.nio.file.Path> files = Files.walk(base)) {
                for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String relative = base.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                    found.add(Class.forName(pkg + "." + relative.substring(0, relative.length() - ".class".length())));
                }
            }
        }
        return found;
    }

    /**
     * Reads the full path of a handler.
     *
     * @param type the resource class
     * @param method the handler
     * @return the class path followed by the method path
     */
    private static String path(Class<?> type, Method method) {
        Path classPath = type.getAnnotation(Path.class);
        Path methodPath = method.getAnnotation(Path.class);
        String full = (classPath == null ? "" : classPath.value()) + (methodPath == null ? "" : "/" + methodPath.value());
        return full.replaceAll("/+", "/");
    }

    /**
     * Every back-office screen handler declares its feature and none keeps a
     * fixed role.
     *
     * @throws Exception never, the classes are compiled
     */
    @Test
    void everyScreenHandlerDeclaresItsFeature() throws Exception {
        List<String> missing = new ArrayList<>();
        List<String> fixedRoles = new ArrayList<>();
        int declared = 0;
        List<Class<?>> types = new ArrayList<>(classes("com.intermarche.edge.ui"));
        types.addAll(classes("com.intermarche.edge.security"));
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(GET.class) && !method.isAnnotationPresent(POST.class)) {
                    continue;
                }
                String path = path(type, method);
                if (!path.startsWith("/admin") && !path.startsWith("/dashboard")) {
                    continue;
                }
                String name = type.getSimpleName() + "." + method.getName();
                if (method.isAnnotationPresent(RolesAllowed.class)) {
                    fixedRoles.add(name);
                }
                if (method.isAnnotationPresent(FeatureAccess.class)) {
                    declared++;
                } else if (!SELF_SERVICE.contains(name)) {
                    missing.add(name + " " + path);
                }
            }
        }
        assertEquals(List.of(), missing);
        assertEquals(List.of(), fixedRoles);
        assertTrue(declared > 190, "declared " + declared);
    }
}
