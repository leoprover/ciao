package leo.modules.ciao

import leo.datastructures.TPTP
import leo.datastructures.TPTP.AnnotatedFormula.FormulaType
import leo.datastructures.TPTP.{TFF, TFFAnnotated}
import leo.datastructures.TPTP.{THF, THFAnnotated}
import leo.modules.tptputils.{SyntaxTransform, TPTPTransformException}

import scala.annotation.tailrec

class ProblemAugment(problem: Seq[TPTP.AnnotatedFormula]) {
  import leo.modules.ciao.ProblemAugment._

  // todo: proper logging utility
  val logging: Boolean = false
  def maybePrintln(s: String) = if (logging) println(s)

  /* init */
  // transform to TFF (if it has been FOF)
  private val problemAsTFF: Seq[TPTP.TFFAnnotated] = {
    try {
      SyntaxTransform.transformProblem(FormulaType.TFF, TPTP.Problem(Seq.empty, problem, Map.empty), addMissingTypeDeclarations = true).formulas.map(_.asInstanceOf[TPTP.TFFAnnotated])
    } catch {
      case _:TPTPTransformException => throw new IllegalArgumentException("Only FOF and TFF input allowed.")
    }
  }
  // split. assumes all types are declared
  private val (declaredSorts, declaredTypes, defsFormulas, axiomFormulas, conjectureFormulas) = splitProblemAsTFF(problemAsTFF)
  private val symbolsUsedInConjecture: Set[String] = conjectureFormulas.flatMap(_.symbols).toSet
  private var usedFreshSymbols: Set[String] = Set.empty

  case class definingFormulas(premises: Set[TPTP.TFF.Formula],
                        conclusion: TPTP.TFF.Formula,
                        context: Context){
    def collectFV: Set[String] = premises.flatMap(freeVars(_)) ++ freeVars(conclusion)
  }

  def apply(params: Set[AugmentParameter]): Set[Seq[TPTP.THFAnnotated]] = {
    maybePrintln(s"conjecture(s): ${conjectureFormulas.map(_.pretty)}")

    //// FORMULAE COLLECTION
    val definingFormulae = initPossiblyInterestingAxioms(defsFormulas ++ axiomFormulas)

    //// FILTERING
    // only those that define symbols occurring in the conjecture
    val relevantDefiningFormulae: Map[String, Set[definingFormulas]] = definingFormulae.filter { case (key, _) => symbolsUsedInConjecture.contains(key) }
    // only those that have at least one base-case and one constructor
    val sufficientDefiningFormulae: Map[String, Set[definingFormulas]] =
      relevantDefiningFormulae.filter { case (_, defs) =>
        defs.exists(_.premises.isEmpty) && defs.exists(_.premises.nonEmpty)
      }

    //// GENERATING NOTATIONS
    sufficientDefiningFormulae.map(entry => generateDefs(entry._1, entry._2)).toSet
  }

  // define methods for detecting different kinds of formulae used to define inductive structures
  case class SplitDisj(positiveLits: Set[TPTP.TFF.Formula],
                       negativeLits: Set[TPTP.TFF.Formula])

  /** Traverse Disjunction and split into positive and negative literals. Leading negation of negative literals is removed. */
  def splitDisj(disj: TPTP.TFF.Formula): SplitDisj = {
    disj match {
      // disjunction
      case TFF.BinaryFormula(TFF.|, lhs, rhs) =>
        val resLhs = splitDisj(lhs)
        val resRhs = splitDisj(rhs)
        SplitDisj(resLhs.positiveLits ++ resRhs.positiveLits, resLhs.negativeLits ++ resRhs.negativeLits)
      // negative literals
      case TFF.UnaryFormula(TFF.~, t) =>
        SplitDisj(Set.empty, Set(t))
      // positive literals
      case _ =>
        SplitDisj(Set(disj), Set.empty)
    }
  }

  def findHornClause(disj: TPTP.TFF.Formula, context: Context): Option[(String, definingFormulas)] = {
    val split = splitDisj(disj)
    maybePrintln(s"split ${disj.pretty} into ${split.positiveLits.map(_.pretty)} and ${split.negativeLits.map(_.pretty)}")
    if (split.positiveLits.size == 1) {
      val headLit = split.positiveLits.head
      headLit match {
        case TFF.AtomicFormula(hdSym, _) =>
          Some((hdSym, definingFormulas(split.negativeLits, headLit, context)))
        case _ => None
      }
    } else None
  }

  case class SplitImp(premises: Set[TPTP.TFF.Formula])

  def splitConj(impl: TPTP.TFF.Formula): SplitImp = {
    impl match {
      case TFF.BinaryFormula(TFF.&, lhs, rhs) =>
        val resRhs = splitConj(rhs)
        SplitImp(resRhs.premises + lhs)

      case _ =>
        SplitImp(Set(impl))
    }
  }

  def findImplDef(body: TPTP.TFF.Formula, res: TPTP.TFF.Formula, context: Context): Option[(String, definingFormulas)] = {
    val split = splitConj(body)
    res match {
      case TFF.AtomicFormula(hdSym, _) =>
        Some((hdSym, definingFormulas(split.premises, res, context)))
      case _ => None
    }
  }

  private[this] def initPossiblyInterestingAxioms(axioms: Seq[TPTP.TFFAnnotated]): Map[String, Set[definingFormulas]] = {

    // Map for collecting all formulae defining a given symbol
    var allDefFormulas: Map[String, Set[definingFormulas]] = Map.empty.withDefaultValue(Set.empty)

    val axiomsIt = axioms.iterator
    while (axiomsIt.hasNext) {
      val axiom = axiomsIt.next()

      axiom.formula match {
        case TFF.Logical(formula) =>

          maybePrintln(s"looking for candidates for inductive structure ${formula.pretty} ($formula)")
          val (context, matrix) = stripPrefixQuantifier(formula)
          matrix match {

            case t@TFF.AtomicFormula(hd, _) =>
              val basePred = definingFormulas(Set.empty,t,context)//BasePred(t, context)
              allDefFormulas = allDefFormulas + (hd -> (allDefFormulas(hd) + basePred))

            case disj@TFF.BinaryFormula(TFF.|, _, _) =>
              val maybeHornClause = findHornClause(disj, context)
              maybeHornClause match {
                case Some((defSym, foundHornClause)) =>
                  maybePrintln(s"found horn clause for symbol $defSym: ${formula.pretty}")
                  allDefFormulas = allDefFormulas + (defSym -> (allDefFormulas(defSym) + foundHornClause))
                case None =>
                  // do nothing
                  maybePrintln(s"not a horn clause: ${formula.pretty}")
              }

            case TFF.BinaryFormula(TFF.Impl, body, res) =>
              val maybeImpDef = findImplDef(body, res, context)
              maybeImpDef match {
                case Some((defSym, foundImpDef)) =>
                  maybePrintln(s"found horn clause for symbol $defSym: ${formula.pretty}")
                  allDefFormulas = allDefFormulas + (defSym -> (allDefFormulas(defSym) + foundImpDef))
                case None =>
                  // do nothing
                  maybePrintln(s"not a implication based definition: ${formula.pretty}")
              }

            // todo: also handle other kinds of inducive definitions (nested implications, equations, <=> based, ...)

            case _ => maybePrintln(s"did not match ${formula.pretty}")
          }
        case _ => // skip
      }
    }
    allDefFormulas
  }

  def translateAndRename(t: TPTP.TFF.Formula, hd: String, predVarName: String) = {
    val holPred = leo.modules.tptputils.SyntaxTransform.tffLogicFormulaToTHF(t)
    replaceSymbolByVariableInTHF(holPred, hd, predVarName)
  }

  def maybeQuantify(t: TPTP.THF.Formula, freeVars: Set[String], context: Context) = {
    val fvs = freeVars.map { fv =>
      val ty = tffTypeToTHFType(context(fv))
      (fv, ty)
    }.toSeq

    if (fvs.nonEmpty) THF.QuantifiedFormula(THF.!, fvs, t) else t
  }

  /** for a given definingFormulas instance, replace occurrences of a given symbol with a given variable and build the implication based definition body*/
  def buildImpBased(def0: definingFormulas, hd: String, predVarName: String) = {

    val fvs = def0.collectFV
    val transHd = translateAndRename(def0.conclusion, hd, predVarName)

    val imp = if (def0.premises.nonEmpty) {
      val conjunction: TPTP.THF.Formula = def0.premises.tail.foldLeft[TPTP.THF.Formula](translateAndRename(def0.premises.head, hd: String, predVarName: String)) { case (inc, next) =>
        THF.BinaryFormula(THF.&, inc, translateAndRename(next, hd: String, predVarName: String))
      }
      TPTP.THF.BinaryFormula(THF.Impl, conjunction, transHd)
    } else transHd

    maybeQuantify(imp, fvs, def0.context)
  }

  def generateDefs(hd: String, defs: Set[definingFormulas]): Seq[THFAnnotated] = {

    /////////////////////////////
    // DEF1: Notation for fulfilling all properties collected based on problem formulae

    val def1SymbolName = freshSymbol("ind", usedFreshSymbols union declaredTypes.keySet union declaredSorts)
    usedFreshSymbols += def1SymbolName
    val def1SymbolTerm = THF.FunctionTerm(def1SymbolName, Seq.empty)

    //// TYPE DECLARATION
    // type of def1 == type of identified predicate -> o
    val tyIndPred = declaredTypes(hd)
    val tyAsThf = tffTypeToTHFType(tyIndPred)
    val def1SymbolTy = THF.BinaryFormula(THF.FunTyConstructor, tyAsThf, THF.FunctionTerm("$o", Seq.empty))
    val def1_type_annotated = THFAnnotated(s"${def1SymbolName}_decl", "type", THF.Typing(def1SymbolName, def1SymbolTy), None)


    //// NOTATION AXIOM
    // pre-process collected formulae by replacing the predicate symbol with a fresh var
    val allFreeVars = defs.flatMap(_.collectFV)
    val def1PredVarName = freshSymbol("R", allFreeVars)
    val def1PredVar = THF.Variable(def1PredVarName)
    // renamed and normalized to implication form version of every collected formula
    val def1Implications: Set[TPTP.THF.Formula] = defs.map(def00 => buildImpBased (def00, hd: String, def1PredVarName: String))
    // conjunction of all the reusulting formulae
    val def1Conjunction: TPTP.THF.Formula = def1Implications.tail.foldLeft[TPTP.THF.Formula](def1Implications.head) { case (inc, next) =>
      THF.BinaryFormula(THF.&, inc, next)
    }
    // abstraction over the newly introduced variable
    val def1Abstraction = TPTP.THF.QuantifiedFormula(THF.^, Seq((def1PredVarName, tyAsThf)), def1Conjunction)
    // generate actual annotated formulae
    val def1 = THF.BinaryFormula(THF.Eq, def1SymbolTerm, def1Abstraction)
    val def1_annotated = THFAnnotated(def1SymbolName, "axiom", THF.Logical(def1), None)


    /////////////////////////////
    // DEF2: General membership in closure over structure described in def1

    val def2SymbolName = freshSymbol(s"intersect_ind", usedFreshSymbols union declaredTypes.keySet union declaredSorts)
    usedFreshSymbols += def2SymbolName
    val def2SymbolTerm = THF.FunctionTerm(def2SymbolName, Seq.empty)

    //// TYPE DECLARATION
    val (paraTypes, _) = argumentAndResultTypes(tyIndPred)
    val def2Ty = mkFunType(paraTypes.map(tffTypeToTHFType), THF.FunctionTerm("$o", Seq.empty))
    val def2_type_annotated = THFAnnotated(s"${def2SymbolName}_decl", "type", THF.Typing(def2SymbolName, def2Ty), None)


    //// NOTATION AXIOM
    val def2PredVarName = freshSymbol("R", Set.empty)
    val def2PredVar = THF.Variable(def2PredVarName)
    // create variables to instanciate the variable encoding the inductive predicate with
    val holParaTypes = paraTypes.map(tffTypeToTHFType)
    val freshAbstractionVariables = freshSymbols("A", Set.empty, holParaTypes.size).zip(holParaTypes)
    val def2VarApplication = freshAbstractionVariables.foldLeft(THF.FunctionTerm(def2PredVarName, Seq.empty).asInstanceOf[THF.Formula]) { case (inc, vari) => THF.BinaryFormula(THF.App, inc, THF.Variable(vari._1)) }
    // construct implication saying "if def2PredVar is closure over def1, assert membership"
    val appOfDef1 = THF.BinaryFormula(THF.App, def1SymbolTerm, def2PredVar)
    val implDef2 = THF.BinaryFormula(THF.Impl, appOfDef1, def2VarApplication)
    // binding of all variables
    val def2Quantification = THF.QuantifiedFormula(THF.!, Seq((def2PredVarName, tyAsThf)), implDef2)
    val def2Abstraction = TPTP.THF.QuantifiedFormula(THF.^, freshAbstractionVariables, def2Quantification)
    // compose definitions
    val def2 = THF.BinaryFormula(THF.Eq, def2SymbolTerm, def2Abstraction)
    val def2_annotated = THFAnnotated(def2SymbolName, "axiom", THF.Logical(def2), None)


    /////////////////////////////
    // ADDITION: Special instances of def2

    // find subterms in conjecture that may be relevant (criterium: they are applied to inductive predicate in conjecture)
    val relevantSubterms: Seq[(TFF.Term, Int)] = conjectureFormulas.flatMap { conj =>
      conj.formula match {
        case TFF.Logical(formula) =>
          termsAppliedToPredicate(formula, hd)

        case _ => Seq.empty[(TFF.Term, Int)]
      }
    }

    // find those terms that are applications and extract head symbols
    val relevantFunctions = relevantSubterms.map { term =>
      term._1 match {
        case TFF.AtomicTerm(f, _) =>
          Some((f, term._2))
        case _ => None
      }
    }.flatten.map(hd0 => (hd0._1, declaredTypes(hd0._1), hd0._2))


    val additionalNotations = relevantFunctions.map { funHd =>
      val nameForPreciate_special = freshSymbol(s"intersect_ind_${funHd._1}", usedFreshSymbols union declaredTypes.keySet union declaredSorts)
      usedFreshSymbols += nameForPreciate_special

      // for each function, instanciate with fresh variables and assert membership in closure (def2)
      // generate fresh variables and apply them to function symbol
      val (paraTypesFunc, _) = argumentAndResultTypes(funHd._2)
      val holParaTypesFun = paraTypesFunc.map(tffTypeToTHFType)
      val freshAbstractionVariables0 = freshSymbols("A", Set.empty, holParaTypesFun.size).zip(holParaTypesFun)
      val funHdApp = freshAbstractionVariables0.foldLeft(THF.FunctionTerm(funHd._1, Seq.empty).asInstanceOf[THF.Formula]) { case (inc, vari) => THF.BinaryFormula(THF.App, inc, THF.Variable(vari._1)) }

      // create another set of fresh variables, this time to instanciate other argument positions of def2 (if any)
      val freshAbstractionVariablesForRInters0 = freshSymbols("A", freshAbstractionVariables0.map(_._1).toSet, (holParaTypes.size)).zip((holParaTypes))
      val freshAbstractionVariablesForRInters = freshAbstractionVariablesForRInters0.map(va => THF.Variable(va._1))
      // Seq of arguments to be applied to def2 == fresh vars with inst. f at correct position
      val argsForDef2 = freshAbstractionVariablesForRInters.updated(funHd._3, funHdApp)
      val appliedSymbolForRInters = argsForDef2.foldLeft(THF.FunctionTerm(def2SymbolTerm.f, Seq.empty).asInstanceOf[THF.Formula]) { case (inc, vari) => THF.BinaryFormula(THF.App, inc, vari) }

      // abstract over all fresh variables
      val specialDefAllVars = freshAbstractionVariables0 ++ freshAbstractionVariablesForRInters0.patch(funHd._3, Nil, 1)
      val finalAbstraction = TPTP.THF.QuantifiedFormula(THF.^, specialDefAllVars, appliedSymbolForRInters)

      // todo: if we have mulitple args, also create notation that asserta all of them at once

      // TYPE
      val def3Ty = mkFunType(specialDefAllVars.map(_._2), THF.FunctionTerm("$o", Seq.empty))
      val def3_type_annotated = THFAnnotated(s"${nameForPreciate_special}_decl", "type", THF.Typing(nameForPreciate_special, def3Ty), None)

      // NOTATION AXIOM
      val def3 = THF.BinaryFormula(THF.Eq, THF.FunctionTerm(nameForPreciate_special, Seq.empty), finalAbstraction)
      val def3_annotated = THFAnnotated(nameForPreciate_special, "axiom", THF.Logical(def3), None)

      Seq(def3_type_annotated, def3_annotated)
    }

    maybePrintln(s"all additional axioms: ${additionalNotations.flatten.map(_.pretty)}")

    Seq(def1_type_annotated, def1_annotated, def2_type_annotated, def2_annotated) ++ additionalNotations.flatten
  }

  /** Traverse formula and find all terms applied to a specific predicate */
  def termsAppliedToPredicate(formula: TFF.Formula, targetPredicate: String): Seq[(TFF.Term, Int)] = formula match {

    case TFF.AtomicFormula(pred, args) if pred == targetPredicate =>
      args.zipWithIndex

    case TFF.AtomicFormula(_, args) =>
      Seq.empty

    case TFF.Equality(left, right) =>
      Seq.empty

    case TFF.Inequality(left, right) =>
      Seq.empty

    case TFF.UnaryFormula(_, body) =>
      termsAppliedToPredicate(body, targetPredicate)

    case TFF.BinaryFormula(_, left, right) =>
      termsAppliedToPredicate(left, targetPredicate) ++
        termsAppliedToPredicate(right, targetPredicate)

    case TFF.QuantifiedFormula(_, _, body) =>
      termsAppliedToPredicate(body, targetPredicate)

    case TFF.FormulaVariable(_) =>
      Seq.empty

    case TFF.ConditionalFormula(condition, _, _) =>
      termsAppliedToPredicate(condition, targetPredicate)

    case TFF.LetFormula(_, _, _) =>
      Seq.empty

    case TFF.Assignment(_, _) =>
      Seq.empty

    case TFF.MetaIdentity(_, _) =>
      Seq.empty

    case TFF.NonclassicalPolyaryFormula(_, args) =>
      args.flatMap(arg => termsAppliedToPredicate(arg, targetPredicate))
  }

  def mkAppChain(head: THF.Formula, args: Seq[THF.Formula]): THF.Formula = {
    args.foldLeft(head) { case (acc, arg) =>
      THF.BinaryFormula(THF.App, acc, arg)
    }
  }

  /** traverse formula and replace all occurrences of a given symbol with a variable */
  def replaceSymbolByVariableInTHF(formula: THF.Formula, oldSymbol: String, variable: String): THF.Formula = formula match {

    case THF.FunctionTerm(f, args) if f == oldSymbol =>
      val newArgs =
        args.map(arg => replaceSymbolByVariableInTHF(arg, oldSymbol, variable))

      mkAppChain(THF.Variable(variable), newArgs)

    case THF.FunctionTerm(f, args) =>
      THF.FunctionTerm(f, args.map(arg => replaceSymbolByVariableInTHF(arg, oldSymbol, variable)))

    case v@THF.Variable(_) =>
      v

    case THF.UnaryFormula(conn, body) =>
      THF.UnaryFormula(conn, replaceSymbolByVariableInTHF(body, oldSymbol, variable))

    case THF.BinaryFormula(conn, left, right) =>
      THF.BinaryFormula(conn, replaceSymbolByVariableInTHF(left, oldSymbol, variable), replaceSymbolByVariableInTHF(right, oldSymbol, variable))

    case THF.QuantifiedFormula(q, variableList, body) =>
      val newVariableList =
        variableList.map { case (name, typ) =>
          (name, replaceSymbolByVariableInTHF(typ, oldSymbol, variable))
        }

      THF.QuantifiedFormula(q, newVariableList, replaceSymbolByVariableInTHF(body, oldSymbol, variable))

    case THF.Tuple(elements) =>
      THF.Tuple(elements.map(elem => replaceSymbolByVariableInTHF(elem, oldSymbol, variable)))

    case THF.ConditionalTerm(condition, thn, els) =>
      THF.ConditionalTerm(
        replaceSymbolByVariableInTHF(condition, oldSymbol, variable),
        replaceSymbolByVariableInTHF(thn, oldSymbol, variable),
        replaceSymbolByVariableInTHF(els, oldSymbol, variable)
      )

    case THF.LetTerm(typing, binding, body) =>
      val newTyping =
        typing.map { case (name, typ) =>
          (name, replaceSymbolByVariableInTHF(typ, oldSymbol, variable))
        }

      val newBinding =
        binding.map { case (lhs, rhs) =>
          (replaceSymbolByVariableInTHF(lhs, oldSymbol, variable), replaceSymbolByVariableInTHF(rhs, oldSymbol, variable))
        }

      THF.LetTerm(newTyping, newBinding, replaceSymbolByVariableInTHF(body, oldSymbol, variable))

    case THF.NonclassicalPolyaryFormula(conn, args) =>
      THF.NonclassicalPolyaryFormula(
        replaceSymbolByVariableInNonclassicalOperator(conn, oldSymbol, variable),
        args.map(arg => replaceSymbolByVariableInTHF(arg, oldSymbol, variable))
      )

    case d@THF.DistinctObject(_) => d

    case n@THF.NumberTerm(_) => n

    case c@THF.ConnectiveTerm(_) => c

    case d@THF.DefinedTH1ConstantTerm(_) => d
  }

  def replaceSymbolByVariableInNonclassicalOperator(conn: THF.VararyNonclassicalOperator, oldSymbol: String, variable: String): THF.VararyNonclassicalOperator = conn match {

    case THF.NonclassicalLongOperator(name, index, parameters) =>
      THF.NonclassicalLongOperator(
        name,
        index.map(idx => replaceSymbolByVariableInTHF(idx, oldSymbol, variable)),
        parameters.map { case (k, v) =>
          (replaceSymbolByVariableInTHF(k, oldSymbol, variable), replaceSymbolByVariableInTHF(v, oldSymbol, variable))
        }
      )

    case THF.NonclassicalBox(index) =>
      THF.NonclassicalBox(index.map(idx => replaceSymbolByVariableInTHF(idx, oldSymbol, variable)))

    case THF.NonclassicalDiamond(index) =>
      THF.NonclassicalDiamond(index.map(idx => replaceSymbolByVariableInTHF(idx, oldSymbol, variable)))

    case THF.NonclassicalCone(index) =>
      THF.NonclassicalCone(index.map(idx => replaceSymbolByVariableInTHF(idx, oldSymbol, variable)))
  }


  private def tffTypeToTHFType(tffType: TFF.Type): THF.Type = {
    tffType match {
      case TFF.AtomicType(name, args) => THF.FunctionTerm(name, args.map(tffTypeToTHFType))
      case TFF.MappingType(left, right) => left.foldRight(tffTypeToTHFType(right)) { case (l,acc) =>
        THF.BinaryFormula(THF.FunTyConstructor, tffTypeToTHFType(l), acc)
      }
      case TFF.QuantifiedType(variables, body) =>
        val vari0 = variables map { case (name, ty) =>
          ty match {
            case Some(value) => (name, tffTypeToTHFType(value))
            case None => (name, THF.FunctionTerm("$i", Seq.empty))
          }
        }
        THF.QuantifiedFormula(THF.!>, vari0, tffTypeToTHFType(body))
      case TFF.TypeVariable(name) => THF.Variable(name)
      case TFF.TupleType(components) => THF.Tuple(components.map(tffTypeToTHFType))
    }
  }

  private[this] def freshSymbol(basename: String, used: Set[String]): String = {
    var start = 0
    while (used.contains(s"$basename$start")) start += 1

    s"$basename$start"
  }

  private[this] def freshSymbols(basename: String, used: Set[String], amount: Int): Seq[String] = {
    if (amount <= 0) Seq.empty
    else {
      var start = 0
      var results: Seq[String] = Seq.empty
      while (results.size < amount) {
        while (used.contains(s"$basename$start")) {
          start += 1
        }
        results = s"$basename$start" +: results
        start += 1
      }
      results
    }
  }

  private[this] def splitProblemAsTFF(formulas: Seq[TPTP.TFFAnnotated]): (Set[String],
                                                                     Map[String, TPTP.TFF.Type],
                                                                     Seq[TPTP.TFFAnnotated],
                                                                     Seq[TPTP.TFFAnnotated],
                                                                     Seq[TPTP.TFFAnnotated]) = {
    var sorts: Set[String] = Set.empty
    var types: Map[String, TPTP.TFF.Type] = Map.empty
    var defs, axioms, conjectures: Seq[TPTP.TFFAnnotated] = Seq.empty
//    formulas.flatMap(s => s.formula.symbols)
    val formulasIt = formulas.iterator
    while (formulasIt.hasNext) {
      val formula = formulasIt.next()
      formula.role match {
        case "type" => formula.formula match {
          case TPTP.TFF.Typing(atom, TPTP.TFF.AtomicType("$tType", Seq())) =>
            sorts += atom
          case TPTP.TFF.Typing(atom, ty) =>
            types += (atom -> ty)
          case f => throw new RuntimeException(s"Type formula ill-formed: '${f.pretty}'.")
        }
        case "definition" =>
          defs = formula +: defs
        case "axiom" | "hypothesis" | "lemma" =>
          axioms = formula +: axioms
        case "conjecture" | "negated_conjecture" => // Treat the same (for now?)
          conjectures = formula +: conjectures
        case role => throw new RuntimeException(s"Unsupported formula role '$role'.")
      }
    }
    (sorts, types, defs, axioms, conjectures)
  }

  private[this] def stripPrefixQuantifier(formula: TFF.Formula): (Context, TFF.Formula) = stripPrefixQuantifier0(formula, Map.empty)
  @tailrec
  private[this] def stripPrefixQuantifier0(formula: TFF.Formula, context0: Context): (Context, TFF.Formula) = {
    formula match {
      case TFF.QuantifiedFormula(_, variables, body) =>
        val context = variables.map { case (name, maybeTy) =>
          maybeTy match {
            case Some(value) => (name, value)
            case None => (name, TFF.AtomicType("$i", Seq.empty))
          }
        }.toMap
        stripPrefixQuantifier0(body, context0 ++ context)
      case _ => (context0, formula)
    }
  }

  private[this] def argumentAndResultTypes(typ: TFF.Type): (Seq[TFF.Type], TFF.Type) = {
    typ match {
      case TFF.MappingType(left, right) => (left, right)
      case _ => (Seq.empty, typ)
    }
  }
  private[this] def mkFunType(paraTypes: Seq[THF.Type], goalType: THF.Type): THF.Type = {
    if (paraTypes.isEmpty) goalType
    else THF.BinaryFormula(THF.FunTyConstructor, paraTypes.head, mkFunType(paraTypes.tail, goalType))
  }
  private[this] def mkApp(hd: THF.Formula, args: Seq[THF.Formula]): THF.Formula = {
    args.foldLeft(hd)(THF.BinaryFormula(THF.App, _, _))
  }
  private[this] def freeVars(formula: TFF.Formula): Set[String] = {
    formula match {
      case TFF.AtomicFormula(_, args) => args.flatMap(freeVars).toSet
      case TFF.QuantifiedFormula(_, variableList, body) =>
        freeVars(body) -- variableList.map(_._1)
      case TFF.UnaryFormula(_, body) => freeVars(body)
      case TFF.BinaryFormula(_, left, right) => freeVars(left) ++ freeVars(right)
      case TFF.Equality(left, right) => freeVars(left) ++ freeVars(right)
      case TFF.Inequality(left, right) => freeVars(left) ++ freeVars(right)
      case TFF.FormulaVariable(name) => Set(name)
      case TFF.ConditionalFormula(_, thn, els) => freeVars(thn) ++ freeVars(els)
      case TFF.LetFormula(_, _, body) => freeVars(body)
      case TFF.NonclassicalPolyaryFormula(_, args) => args.flatMap(freeVars).toSet
      case _ => Set.empty
    }
  }
  private[this] def freeVars(term: TFF.Term): Set[String] = {
    term match {
      case TFF.AtomicTerm(_, args) => args.flatMap(freeVars).toSet
      case TFF.Variable(name) => Set(name)
      case TFF.Tuple(elements) => elements.flatMap(freeVars).toSet
      case TFF.FormulaTerm(formula) => freeVars(formula)
      case _ => Set.empty
    }
  }


}
object ProblemAugment {
  sealed abstract class AugmentParameter
  object CollectOutput extends AugmentParameter

  private type Context = Map[String, TPTP.TFF.Type]
  private final case class AugmentedTFFTerm(f: TFF.Term, c: Context) {

    override def equals(obj: Any): Boolean = obj match {
      case fformula: AugmentedTFFTerm => f == fformula.f
      case _ => super.equals(obj)
    }

    override def hashCode(): Int = f.hashCode()

    def pretty: String = s"[${c.map(entry => s"${entry._1}:${entry._2.pretty}").mkString(",")}]: ${f.pretty}"
  }

  final def apply(problem: TPTP.Problem): Set[Seq[TPTP.THFAnnotated]] = apply(problem, Set.empty)
  final def apply(problem: TPTP.Problem, params: Set[AugmentParameter]): Set[Seq[TPTP.THFAnnotated]] = apply(problem.formulas, params)
  final def apply(problem: Seq[TPTP.AnnotatedFormula]): Set[Seq[TPTP.THFAnnotated]] = apply(problem, Set.empty)
  final def apply(problem: Seq[TPTP.AnnotatedFormula], params: Set[AugmentParameter]): Set[Seq[TPTP.THFAnnotated]] = new ProblemAugment(problem).apply(params)
}
