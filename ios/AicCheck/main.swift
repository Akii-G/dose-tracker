// Checks Aic in MedicineList.swift against the values test/test_model.py checks on the PC, so the
// iPhone reads the box the same way. Run by the iOS workflow:
//   swiftc -o aic-check ios/Dose/MedicineList.swift ios/AicCheck/main.swift && ./aic-check
import Foundation

func check(_ ok: Bool, _ what: String) { if !ok { print("AIC check failed: \(what)"); exit(1) } }

check(Aic.fromText("A012745093") == "012745093", "typed")
check(Aic.fromText("tachipirina") == nil, "not a number")
check(Aic.fromBarcode("A012745093") == "012745093", "Code 39")
check(Aic.fromBarcode("0CSSBD") == "012345676", "Code 32")
check(Aic.fromBarcode("]d20108012345678906" + "21ABC123\u{1D}" + "17271231" + "10L01\u{1D}" + "716012745093") == "012745093", "DataMatrix")
check(Aic.fromBarcode("010801234567890617271231716012745093") == "012745093", "DataMatrix without separators")
check(Aic.fromBarcode("hello") == nil, "nothing")
func item(_ s: String, _ packs: String) -> MedicineList.Item {
    MedicineList.Item(name: "Tachipirina", strength: s, form: "Compressa", ingredient: "Paracetamolo",
                      foldedName: "tachipirina", foldedIngredient: "paracetamolo", packs: packs)
}
let all = [item("500 mg", "012745:093,105"), item("1000 mg", "012745:220")]
check(Aic.find(all, "012745220")?.strength == "1000 mg", "exact pack")
check(Aic.find(all, "012745999")?.strength == "500 mg", "another pack")
check(Aic.find(all, "999999999") == nil, "unknown")
print("AIC ok")
