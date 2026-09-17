package com.modelcollab.generator.service;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Empaqueta el directorio de un proyecto generado (ver
 * {@link SpringBootGeneratorService}) en un {@code .zip} en memoria, listo para
 * devolver como descarga desde {@code GeneratorController}.
 */
@Service
public class ZipPackagingService {

    /**
     * @param projectRoot directorio raíz del proyecto generado (todo su contenido se
     *                     agrega al zip con rutas relativas a esta raíz)
     * @return los bytes completos del archivo .zip
     */
    public byte[] zipDirectory(Path projectRoot) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buffer)) {
            try (var paths = Files.walk(projectRoot)) {
                paths.filter(Files::isRegularFile).sorted().forEach(path -> {
                    String relativePath = projectRoot.relativize(path).toString().replace('\\', '/');
                    try (InputStream in = Files.newInputStream(path)) {
                        zos.putNextEntry(new ZipEntry(relativePath));
                        in.transferTo(zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException("No se pudo agregar al zip: " + relativePath, e);
                    }
                });
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        return buffer.toByteArray();
    }

    /**
     * Borra recursivamente el directorio temporal del proyecto generado una vez
     * empaquetado (el controlador la llama en un {@code finally}, después de leer
     * los bytes del zip).
     */
    public void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort: es un directorio temporal del SO, no bloqueante para la respuesta HTTP.
                }
            });
        } catch (IOException ignored) {
            // Idem: limpieza best-effort.
        }
    }
}
