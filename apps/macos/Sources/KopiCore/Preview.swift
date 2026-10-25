// Clipboard preview text and card-number detection (see Global Constraints).
public enum PreviewInput: Equatable {
    case text(String, sensitive: Bool)
    case image
}

// Returns true when s holds only ASCII digits (after removing whitespace and
// hyphens), is 13-19 digits long, and passes the Luhn checksum.
public func isCardNumber(_ s: String) -> Bool {
    let digits = cardDigits(s)
    guard (13...19).contains(digits.count), digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { return false }
    var sum = 0
    for (index, char) in digits.reversed().enumerated() {
        var digit = char.wholeNumberValue!
        if index % 2 == 1 {
            digit *= 2
            if digit > 9 { digit -= 9 }
        }
        sum += digit
    }
    return sum % 10 == 0
}

// Card masking wins over the sensitive flag; images show a fixed label;
// plain text is whitespace-collapsed and cut at 40 characters plus "…".
public func preview(_ input: PreviewInput) -> String {
    switch input {
    case .image:
        return "imagen"
    case .text(let text, let sensitive):
        if isCardNumber(text) {
            return "tarjeta •••• \(cardDigits(text).suffix(4))"
        }
        if sensitive {
            return "contenido oculto"
        }
        let collapsed = text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        if collapsed.count > Limits.previewMaxCharacters {
            return String(collapsed.prefix(Limits.previewMaxCharacters)) + "…"
        }
        return collapsed
    }
}

private func cardDigits(_ s: String) -> String {
    s.filter { !$0.isWhitespace && $0 != "-" }
}
