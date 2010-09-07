package com.jogamp.opencl.util;

import com.jogamp.common.JogampRuntimeException;
import com.jogamp.opencl.CL;
import com.jogamp.opencl.CLDevice;
import com.jogamp.opencl.CLPlatform;
import com.jogamp.opencl.CLProperty;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Michael Bien
 */
public class CLUtil {

    public static String clString2JavaString(byte[] chars, int clLength) {
        return clLength==0 ? "" : new String(chars, 0, clLength-1);
    }

    public static String clString2JavaString(ByteBuffer chars, int clLength) {
        if (clLength==0) {
            return "";
        }else{
            byte[] array = new byte[clLength-1]; // last char is always null
            chars.get(array).rewind();
            return new String(array, 0, clLength-1);
        }
    }

    /**
     * Returns true if clBoolean == CL.CL_TRUE.
     */
    public static boolean clBoolean(int clBoolean) {
        return clBoolean == CL.CL_TRUE;
    }

    /**
     * Returns b ? CL.CL_TRUE : CL.CL_FALSE
     */
    public static int clBoolean(boolean b) {
        return b ? CL.CL_TRUE : CL.CL_FALSE;
    }

    /**
     * Reads all platform properties and returns them as key-value map.
     */
    public static Map<String, String> obtainPlatformProperties(CLPlatform platform) {
        return readCLProperties(platform);
    }

    /**
     * Reads all device properties and returns them as key-value map.
     */
    public static Map<String, String> obtainDeviceProperties(CLDevice dev) {
        return readCLProperties(dev);
    }

    private static Map<String, String> readCLProperties(Object obj) {
        try {
            return invoke(listMethods(obj.getClass()), obj);
        } catch (IllegalArgumentException ex) {
            throw new JogampRuntimeException(ex);
        } catch (IllegalAccessException ex) {
            throw new JogampRuntimeException(ex);
        }
    }

    static Map<String, String> invoke(List<Method> methods, Object obj) throws IllegalArgumentException, IllegalAccessException {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (Method method : methods) {
            Object info = null;
            try {
                info = method.invoke(obj);
            } catch (InvocationTargetException ex) {
                info = ex.getTargetException();
            }

            if(info.getClass().isArray()) {
                info = asList(info);
            }

            String value = method.getAnnotation(CLProperty.class).value();
            map.put(value, info.toString());
        }
        return map;
    }

    static List<Method> listMethods(Class<?> clazz) throws SecurityException {
        List<Method> list = new ArrayList<Method>();
        for (Method method : clazz.getDeclaredMethods()) {
            Annotation[] annotations = method.getDeclaredAnnotations();
            for (Annotation annotation : annotations) {
                if (annotation instanceof CLProperty) {
                    list.add(method);
                }
            }
        }
        return list;
    }

    private static List<Number> asList(Object info) {
        List<Number> list = new ArrayList<Number>();
        if(info instanceof int[]) {
            int[] array = (int[]) info;
            for (int i : array) {
                list.add(i);
            }
        }else if(info instanceof long[]) {
            long[] array = (long[]) info;
            for (long i : array) {
                list.add(i);
            }
        }else if(info instanceof float[]) {
            float[] array = (float[]) info;
            for (float i : array) {
                list.add(i);
            }
        }else if(info instanceof double[]) {
            double[] array = (double[]) info;
            for (double i : array) {
                list.add(i);
            }
        }
        return list;
    }

}
