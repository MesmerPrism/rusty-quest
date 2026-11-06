// SPDX-License-Identifier: AGPL-3.0-or-later
package org.json; public class JSONArray { private final java.util.List<Object> values=new java.util.ArrayList<>(); public JSONArray put(Object v){values.add(v);return this;} public Object opt(int index){return index<values.size()?values.get(index):null;} }
