package io.github.mesmerprism.rustyquest.native_renderer;

import android.text.InputType;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host fixture: real input adapter callbacks, retirement and input-type presentation bounds. */
public final class NativeMeshPanelInputTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static String chars(int type,boolean caps,boolean symbols){
        StringBuilder result=new StringBuilder();
        for(String row:NativeMeshUiProducer.TextInputRules.rows(type,caps,symbols))result.append(row);
        return result.toString();
    }
    public static void main(String[] args){
        final ArrayList<String> calls=new ArrayList<String>();
        InputConnection original=(InputConnection)Proxy.newProxyInstance(InputConnection.class.getClassLoader(),
            new Class<?>[]{InputConnection.class},(proxy,method,parameters)->{
                calls.add(method.getName()+((parameters!=null&&parameters.length>0)?":"+parameters[0]:""));
                return method.getReturnType()==boolean.class?Boolean.TRUE:null;
            });
        AtomicBoolean attached=new AtomicBoolean(true);
        NativeMeshUiProducer.TextInputSession first=new NativeMeshUiProducer.TextInputSession(original,attached::get);
        first.commitText("a",1);first.performEditorAction(6);
        check(calls.size()==2&&calls.get(0).equals("commitText:a")&&calls.get(1).equals("performEditorAction:6"),"original editor callbacks changed");
        attached.set(false);first.commitText("must-not-leak",1);first.performEditorAction(6);
        check(calls.size()==2,"retired owner admitted text/action");
        first.close();first.close();attached.set(true);first.commitText("must-not-replay",1);
        check(calls.size()==3&&calls.get(2).equals("finishComposingText"),"close failed to fence reentry or finish once");
        NativeMeshUiProducer.TextInputSession replacement=new NativeMeshUiProducer.TextInputSession(original,attached::get);
        replacement.commitText("b",1);first.commitText("old-button",1);
        check(calls.size()==4&&calls.get(3).equals("commitText:b"),"old editor callback retargeted replacement");replacement.close();
        check(chars(InputType.TYPE_CLASS_NUMBER,false,false).equals("1234567890"),"integer expiry keyboard includes nonnumeric keys");
        check(chars(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED|InputType.TYPE_NUMBER_FLAG_DECIMAL,false,false).equals("1234567890-."),"signed decimal tuning keys lost");
        String printable=chars(InputType.TYPE_CLASS_TEXT,false,false)+chars(InputType.TYPE_CLASS_TEXT,true,false)+chars(InputType.TYPE_CLASS_TEXT,false,true)+" ";
        for(char c=32;c<127;c++)check(printable.indexOf(c)>=0,"ASCII form/token character unavailable: "+(int)c);
        check(chars(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,false,false).equals(chars(InputType.TYPE_CLASS_TEXT,false,false)),"password editor contents leaked into keyboard labels");
        check(NativeMeshUiProducer.TextInputRules.multiline(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE),"JSON multiline Enter lost");
        check(!NativeMeshUiProducer.TextInputRules.multiline(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED),"number flag confused with multiline text");
        System.out.println("NativeMeshPanelInputTest PASS original callback dispatch, editor retirement/no replay, numeric/token/multiline input presentation");
    }
}
