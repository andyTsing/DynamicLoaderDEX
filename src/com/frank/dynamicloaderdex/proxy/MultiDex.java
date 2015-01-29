/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.frank.dynamicloaderdex.proxy;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.zip.ZipFile;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import dalvik.system.DexFile;

/**
 * Monkey patches {@link Context#getClassLoader() the application context class
 * loader} in order to load classes from more than one dex file. The primary
 * {@code classes.dex} must contain the classes necessary for calling this class
 * methods. Secondary dex files named classes2.dex, classes3.dex... found in the
 * application apk will be added to the classloader after first call to
 * {@link #install(Context)}.
 * 
 * <p/>
 * This library provides compatibility for platforms with API level 4 through
 * 20. This library does nothing on newer versions of the platform which provide
 * built-in support for secondary dex files.
 */
public final class MultiDex {
	static final String TAG = "MultiDex";

	private MultiDex() {
	}

	public static void installSecondaryDexes(ClassLoader loader, File dexDir,
			List<File> files) throws IllegalArgumentException,
			IllegalAccessException, NoSuchFieldException,
			InvocationTargetException, NoSuchMethodException, IOException {
		if (!files.isEmpty()) {
			if (Build.VERSION.SDK_INT >= 19) {
				V19.install(loader, files, dexDir);
			} else if (Build.VERSION.SDK_INT >= 14) {
				V14.install(loader, files, dexDir);
			} else {
				V4.install(loader, files);
			}
		}
	}

	/**
	 * Locates a given field anywhere in the class inheritance hierarchy.
	 * 
	 * @param instance
	 *            an object to search the field into.
	 * @param name
	 *            field name
	 * @return a field object
	 * @throws NoSuchFieldException
	 *             if the field cannot be located
	 */
	private static Field findField(Object instance, String name)
			throws NoSuchFieldException {
		for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz
				.getSuperclass()) {
			try {
				Field field = clazz.getDeclaredField(name);
				if (!field.isAccessible()) {
					field.setAccessible(true);
				}
				return field;
			} catch (NoSuchFieldException e) {
				// ignore and search next
			}
		}
		throw new NoSuchFieldException("Field " + name + " not found in "
				+ instance.getClass());
	}

	/**
	 * Locates a given method anywhere in the class inheritance hierarchy.
	 * 
	 * @param instance
	 *            an object to search the method into.
	 * @param name
	 *            method name
	 * @param parameterTypes
	 *            method parameter types
	 * @return a method object
	 * @throws NoSuchMethodException
	 *             if the method cannot be located
	 */
	private static Method findMethod(Object instance, String name,
			Class<?>... parameterTypes) throws NoSuchMethodException {
		for (Class<?> clazz = instance.getClass(); clazz != null; clazz = clazz
				.getSuperclass()) {
			try {
				Method method = clazz.getDeclaredMethod(name, parameterTypes);
				if (!method.isAccessible()) {
					method.setAccessible(true);
				}
				return method;
			} catch (NoSuchMethodException e) {
				// ignore and search next
			}
		}
		throw new NoSuchMethodException("Method " + name + " with parameters "
				+ Arrays.asList(parameterTypes) + " not found in "
				+ instance.getClass());
	}

	/**
	 * Replace the value of a field containing a non null array, by a new array
	 * containing the elements of the original array plus the elements of
	 * extraElements.
	 * 
	 * @param instance
	 *            the instance whose field is to be modified.
	 * @param fieldName
	 *            the field to modify.
	 * @param extraElements
	 *            elements to append at the end of the array.
	 */
	private static synchronized void expandFieldArray(Object instance,
			String fieldName, Object[] extraElements)
			throws NoSuchFieldException, IllegalArgumentException,
			IllegalAccessException {
		Field jlrField = findField(instance, fieldName);
		Object[] original = (Object[]) jlrField.get(instance);
		Object[] combined = (Object[]) Array.newInstance(original.getClass()
				.getComponentType(), original.length + extraElements.length);
		System.arraycopy(original, 0, combined, 0, original.length);
		System.arraycopy(extraElements, 0, combined, original.length,
				extraElements.length);
		jlrField.set(instance, combined);
	}

	/**
	 * Installer for platform versions 19.
	 */
	private static final class V19 {
		private static void install(ClassLoader loader,
				List<File> additionalClassPathEntries, File optimizedDirectory)
				throws IllegalArgumentException, IllegalAccessException,
				NoSuchFieldException, InvocationTargetException,
				NoSuchMethodException {
			/*
			 * The patched class loader is expected to be a descendant of
			 * dalvik.system.BaseDexClassLoader. We modify its
			 * dalvik.system.DexPathList pathList field to append additional DEX
			 * file entries.
			 */
			Field pathListField = findField(loader, "pathList");
			Object dexPathList = pathListField.get(loader);
			ArrayList<IOException> suppressedExceptions = new ArrayList<IOException>();
			expandFieldArray(
					dexPathList,
					"dexElements",
					makeDexElements(dexPathList, new ArrayList<File>(
							additionalClassPathEntries), optimizedDirectory,
							suppressedExceptions));
			if (suppressedExceptions.size() > 0) {
				for (IOException e : suppressedExceptions) {
					Log.w(TAG, "Exception in makeDexElement", e);
				}
				Field suppressedExceptionsField = findField(loader,
						"dexElementsSuppressedExceptions");
				IOException[] dexElementsSuppressedExceptions = (IOException[]) suppressedExceptionsField
						.get(loader);
				if (dexElementsSuppressedExceptions == null) {
					dexElementsSuppressedExceptions = suppressedExceptions
							.toArray(new IOException[suppressedExceptions
									.size()]);
				} else {
					IOException[] combined = new IOException[suppressedExceptions
							.size() + dexElementsSuppressedExceptions.length];
					suppressedExceptions.toArray(combined);
					System.arraycopy(dexElementsSuppressedExceptions, 0,
							combined, suppressedExceptions.size(),
							dexElementsSuppressedExceptions.length);
					dexElementsSuppressedExceptions = combined;
				}
				suppressedExceptionsField.set(loader,
						dexElementsSuppressedExceptions);
			}
		}

		/**
		 * A wrapper around
		 * {@code private static final dalvik.system.DexPathList#makeDexElements}
		 * .
		 */
		private static Object[] makeDexElements(Object dexPathList,
				ArrayList<File> files, File optimizedDirectory,
				ArrayList<IOException> suppressedExceptions)
				throws IllegalAccessException, InvocationTargetException,
				NoSuchMethodException {
			Method makeDexElements = findMethod(dexPathList, "makeDexElements",
					ArrayList.class, File.class, ArrayList.class);
			return (Object[]) makeDexElements.invoke(dexPathList, files,
					optimizedDirectory, suppressedExceptions);
		}
	}

	/**
	 * Installer for platform versions 14, 15, 16, 17 and 18.
	 */
	private static final class V14 {
		private static void install(ClassLoader loader,
				List<File> additionalClassPathEntries, File optimizedDirectory)
				throws IllegalArgumentException, IllegalAccessException,
				NoSuchFieldException, InvocationTargetException,
				NoSuchMethodException {
			/*
			 * The patched class loader is expected to be a descendant of
			 * dalvik.system.BaseDexClassLoader. We modify its
			 * dalvik.system.DexPathList pathList field to append additional DEX
			 * file entries.
			 */
			Field pathListField = findField(loader, "pathList");
			Object dexPathList = pathListField.get(loader);
			expandFieldArray(
					dexPathList,
					"dexElements",
					makeDexElements(dexPathList, new ArrayList<File>(
							additionalClassPathEntries), optimizedDirectory));
		}

		/**
		 * A wrapper around
		 * {@code private static final dalvik.system.DexPathList#makeDexElements}
		 * .
		 */
		private static Object[] makeDexElements(Object dexPathList,
				ArrayList<File> files, File optimizedDirectory)
				throws IllegalAccessException, InvocationTargetException,
				NoSuchMethodException {
			Method makeDexElements = findMethod(dexPathList, "makeDexElements",
					ArrayList.class, File.class);
			return (Object[]) makeDexElements.invoke(dexPathList, files,
					optimizedDirectory);
		}
	}

	/**
	 * Installer for platform versions 4 to 13.
	 */
	private static final class V4 {
		private static void install(ClassLoader loader,
				List<File> additionalClassPathEntries)
				throws IllegalArgumentException, IllegalAccessException,
				NoSuchFieldException, IOException {
			/*
			 * The patched class loader is expected to be a descendant of
			 * dalvik.system.DexClassLoader. We modify its fields mPaths,
			 * mFiles, mZips and mDexs to append additional DEX file entries.
			 */
			int extraSize = additionalClassPathEntries.size();
			StringBuilder path = new StringBuilder();
			String[] extraPaths = new String[extraSize];
			File[] extraFiles = new File[extraSize];
			ZipFile[] extraZips = new ZipFile[extraSize];
			DexFile[] extraDexs = new DexFile[extraSize];
			for (ListIterator<File> iterator = additionalClassPathEntries
					.listIterator(); iterator.hasNext();) {
				File additionalEntry = iterator.next();
				String entryPath = additionalEntry.getAbsolutePath();
				path.append(':').append(entryPath);
				int index = iterator.previousIndex();
				extraPaths[index] = entryPath;
				extraFiles[index] = additionalEntry;
				extraZips[index] = new ZipFile(additionalEntry);
				extraDexs[index] = DexFile.loadDex(entryPath, entryPath
						+ ".dex", 0);
			}
			synchronized (V4.class) {
				Field pathField = findField(loader, "path");
				path.insert(0, (String) pathField.get(loader));
				pathField.set(loader, path.toString());
				expandFieldArray(loader, "mPaths", extraPaths);
				expandFieldArray(loader, "mFiles", extraFiles);
				expandFieldArray(loader, "mZips", extraZips);
				expandFieldArray(loader, "mDexs", extraDexs);
			}
		}
	}
}