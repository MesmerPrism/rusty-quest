// SPDX-License-Identifier: AGPL-3.0-or-later
package android.os; public class Looper { private static Looper main=new Looper(); public static Looper getMainLooper(){return main;} public static void prepareMainLooper(){main=new Looper();} public static void loop(){} }
