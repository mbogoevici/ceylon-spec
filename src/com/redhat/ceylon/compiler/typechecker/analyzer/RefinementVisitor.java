package com.redhat.ceylon.compiler.typechecker.analyzer;


import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkAssignable;
import static com.redhat.ceylon.compiler.typechecker.analyzer.Util.checkIsExactly;

import java.util.ArrayList;
import java.util.List;

import com.redhat.ceylon.compiler.typechecker.model.Class;
import com.redhat.ceylon.compiler.typechecker.model.ClassOrInterface;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Functional;
import com.redhat.ceylon.compiler.typechecker.model.Generic;
import com.redhat.ceylon.compiler.typechecker.model.Getter;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.MethodOrValue;
import com.redhat.ceylon.compiler.typechecker.model.Package;
import com.redhat.ceylon.compiler.typechecker.model.Parameter;
import com.redhat.ceylon.compiler.typechecker.model.ParameterList;
import com.redhat.ceylon.compiler.typechecker.model.ProducedReference;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.TypeParameter;
import com.redhat.ceylon.compiler.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.Value;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

/**
 * Validates some simple rules relating to refinement.
 * 
 * @see TypeHierarchyVisitor for the fancy stuff!
 * 
 * @author Gavin King
 *
 */
public class RefinementVisitor extends Visitor {
    
    @Override public void visit(Tree.Declaration that) {
        super.visit(that);
        Declaration dec = that.getDeclarationModel();
        if (dec!=null) {
            boolean toplevel = dec.getContainer() instanceof Package;
            boolean member = dec.isClassOrInterfaceMember() && 
                    !(dec instanceof Parameter) &&
                    !(dec instanceof TypeParameter); //TODO: what about nested interfaces and abstract classes?!
            
            if (!toplevel && !member) {
                if (dec.isShared()) {
                    that.addError("shared declaration is not a member of a class, interface, or package");
                }
            }
            
            boolean mayBeShared = 
                    dec instanceof MethodOrValue || 
                    dec instanceof ClassOrInterface;
            if (!mayBeShared) {
                if (dec.isShared()) {
                    that.addError("shared member is not a method, attribute, class, or interface");
                }
            }
            
            boolean mayBeRefined = 
                    dec instanceof Getter || 
                    dec instanceof Value || 
                    dec instanceof Method ||
                    dec instanceof Class;
            if (!mayBeRefined) {
                checkNonrefinableDeclaration(that, dec);
            }

            if (!member) {
                checkNonMember(that, dec);
            }
            
            if ( !dec.isShared() ) {
                checkUnshared(that, dec);
            }
            
            if (member) {
                checkMember(that, dec);
                ClassOrInterface declaringType = (ClassOrInterface) dec.getContainer();
                Declaration refined = declaringType.getRefinedMember(dec.getName());
                dec.setRefinedDeclaration(refined);
            }

        }

    }

    private void checkMember(Tree.Declaration that, Declaration dec) {
        ClassOrInterface ci = (ClassOrInterface) dec.getContainer();
        if (dec.isFormal() && ci instanceof Class) {
            Class c = (Class) ci;
            if (!c.isAbstract() && !c.isFormal()) {
                that.addError("formal member belongs to non-abstract, non-formal class");
            }
        }
        List<Declaration> others = ci.getInheritedMembers( dec.getName() );
        if (others.isEmpty()) {
            if (dec.isActual()) {
                that.addError("actual member does not refine any inherited member");
            }
        }
        else {
            for (Declaration refined: others) {
                if (dec instanceof Method) {
                    if (!(refined instanceof Method)) {
                        that.addError("refined declaration is not a method");
                    }
                }
                else if (dec instanceof Class) {
                    if (!(refined instanceof Class)) {
                        that.addError("refined declaration is not a class");
                    }
                }
                else if (dec instanceof TypedDeclaration) {
                    if (refined instanceof Class || refined instanceof Method) {
                        that.addError("refined declaration is not an attribute");
                    }
                    else if (refined instanceof TypedDeclaration) {
                        if ( ((TypedDeclaration) refined).isVariable() && 
                                !((TypedDeclaration) dec).isVariable()) {
                            that.addError("non-variable attribute refines a variable attribute");
                        }
                    }
                }
                if (!dec.isActual()) {
                    that.addError("non-actual member refines an inherited member", 600);
                }
                if (!refined.isDefault() && !refined.isFormal()) {
                    that.addError("member refines a non-default, non-formal member", 500);
                }
                checkRefinedTypeAndParameterTypes(that, dec, ci, refined);
            }
        }
    }

	private void checkRefinedTypeAndParameterTypes(Tree.Declaration that,
			Declaration dec, ClassOrInterface ci, Declaration refined) {
		List<ProducedType> typeArgs = new ArrayList<ProducedType>();
		if (refined instanceof Generic && dec instanceof Generic) {
		    List<TypeParameter> refinedTypeParams = ((Generic) refined).getTypeParameters();
		    List<TypeParameter> refiningTypeParams = ((Generic) dec).getTypeParameters();
		    int refiningSize = refiningTypeParams.size();
		    int refinedSize = refinedTypeParams.size();
		    if (refiningSize!=refinedSize) {
		        that.addError("member does not have the same number of type parameters as refined member");
		    }
		    for (int i=0; i<(refiningSize<=refinedSize ? refiningSize : refinedSize); i++) {
		        TypeParameter refinedTypParam = refinedTypeParams.get(i);
		        TypeParameter refiningTypeParam = refiningTypeParams.get(i);
		        for (ProducedType t: refiningTypeParam.getSatisfiedTypes()) {
		            checkAssignable(refinedTypParam.getType(), t, that, 
		                "member type parameter " + refiningTypeParam.getName() +
		                " has constraint which refined member type parameter " + refinedTypParam.getName() +
		                " does not satisfy");
		        }
		        typeArgs.add(refinedTypParam.getType());
		    }
		}
		ProducedReference refinedMember = ci.getType().getTypedReference(refined, typeArgs);
		ProducedReference refiningMember = ci.getType().getTypedReference(dec, typeArgs);
		checkAssignable(refiningMember.getType(), refinedMember.getType(), that,
		        "member type must be assignable to refined member type");
		if (dec instanceof Functional && refined instanceof Functional) {
		   ParameterList refiningParams = ((Functional) dec).getParameterLists().get(0);
		   ParameterList refinedParams = ((Functional) refined).getParameterLists().get(0);
		   checkParameterTypes(that, refiningMember, refinedMember, refiningParams, refinedParams);
		}
	}

    private void checkUnshared(Tree.Declaration that, Declaration dec) {
        if (dec.isActual()) {
            that.addError("actual member is not shared", 700);
        }
        if (dec.isFormal()) {
            that.addError("formal member is not shared", 700);
        }
        if (dec.isDefault()) {
            that.addError("default member is not shared", 700);
        }
    }

    private void checkNonrefinableDeclaration(Tree.Declaration that,
            Declaration dec) {
        if (dec.isActual()) {
            that.addError("actual declaration is not a getter, simple attribute, or class");
        }
        if (dec.isFormal()) {
            that.addError("formal declaration is not a getter, simple attribute, or class");
        }
        if (dec.isDefault()) {
            that.addError("default declaration is not a getter, simple attribute, or class");
        }
    }

    private void checkNonMember(Tree.Declaration that, Declaration dec) {
        if (dec.isActual()) {
            that.addError("actual declaration is not a member of a class or interface");
        }
        if (dec.isFormal()) {
            that.addError("formal declaration is not a member of a class or interface");
        }
        if (dec.isDefault()) {
            that.addError("default declaration is not a member of a class or interface");
        }
    }

    private void checkParameterTypes(Tree.Declaration that,
            ProducedReference member, ProducedReference refinedMember,
            ParameterList params, ParameterList refinedParams) {
        if (params.getParameters().size()!=refinedParams.getParameters().size()) {
           that.addError("member does not have the same number of parameters as the member it refines");
        }
        else {
            for (int i=0; i<params.getParameters().size(); i++) {
                Parameter rparam = refinedParams.getParameters().get(i);
                ProducedType refinedParameterType = refinedMember.getTypedParameter(rparam).getType();
                Parameter param = params.getParameters().get(i);
                ProducedType parameterType = member.getTypedParameter(param).getType();
                Tree.Type type = getParameterList(that).getParameters().get(i).getType(); //some kind of syntax error
                if (type!=null) {
                    if (refinedParameterType==null || parameterType==null) {
                        type.addError("could not determine if parameter type is the same as the corresponding parameter of refined member");
                    }
                    else {
                        //TODO: consider type parameter substitution!!!
                        checkIsExactly(parameterType, refinedParameterType, type, "type of parameter " + 
                                param.getName() + " is different to type of corresponding parameter " +
                                rparam.getName() + " of refined member");
                    }
                }
            }
        }
    }

    private static Tree.ParameterList getParameterList(Tree.Declaration that) {
        Tree.ParameterList pl;
        if (that instanceof Tree.AnyMethod) {
            pl = ((Tree.AnyMethod) that).getParameterLists().get(0);
        }
        else {
            pl = ((Tree.ClassDefinition) that).getParameterList();
        }
        return pl;
    }
    
}
