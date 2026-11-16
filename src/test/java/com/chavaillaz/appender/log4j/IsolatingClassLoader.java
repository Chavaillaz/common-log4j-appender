package com.chavaillaz.appender.log4j;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the classes whose name starts with a given prefix itself, from the class files its parent finds, and
 * leaves every other class to that parent: what the class loader of a web application, or of an executable JAR
 * nesting its libraries, is to the classes of the application, which the system class loader knows nothing of.
 */
final class IsolatingClassLoader extends ClassLoader {

    private final String prefix;

    /**
     * Creates a class loader defining the classes of the given prefix itself.
     *
     * @param prefix The start of the names of the classes it loads itself, such as the name of a class to load
     *               along with its nested ones
     */
    IsolatingClassLoader(String prefix) {
        super(IsolatingClassLoader.class.getClassLoader());
        this.prefix = prefix;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith(prefix)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            try (InputStream classFile = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (classFile == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = classFile.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

}
