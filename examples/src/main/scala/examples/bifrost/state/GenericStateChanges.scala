package examples.bifrost.state

import examples.bifrost.transaction.box.GenericBox
import scorex.core.transaction.box.proposition.Proposition

class GenericStateChanges[T, P <: Proposition, BX <: GenericBox[P, T]] (val boxIdsToRemove: Set[Array[Byte]], val toAppend: Set[BX])
