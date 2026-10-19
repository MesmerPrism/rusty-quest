// SPDX-License-Identifier: AGPL-3.0-or-later
package android.app.appfunctions; public class AppFunctionException extends Exception { private static final long serialVersionUID=1; public AppFunctionException(String m){super(m);} public int getErrorCode(){return 17;} }
