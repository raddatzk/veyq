import SwiftUI

struct SignInView: View {
    let working: Bool
    let error: String?
    let onSignIn: (String, String, String, String) -> Void

    @State private var server = "veyq.localhost:8080"
    @State private var email = ""
    @State private var password = ""
    @State private var secretKey = ""

    var body: some View {
        Form {
            Section {
                Text("veyq").font(.largeTitle.bold())
                Text("Sign in").font(.headline)
            }
            Section {
                TextField("Server", text: $server)
                    .textContentType(.URL)
                    .autocorrectionDisabled()
                #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                #endif
                TextField("Email", text: $email)
                    .textContentType(.username)
                    .autocorrectionDisabled()
                #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
                #endif
                SecureField("Password", text: $password)
                    .textContentType(.password)
                SecureField("Secret Key", text: $secretKey)
            }
            if let error {
                Section { Text(Self.message(error)).foregroundStyle(.red) }
            }
            Section {
                Button {
                    onSignIn(server, email, password, secretKey)
                } label: {
                    if working { ProgressView() } else { Text("Sign in").frame(maxWidth: .infinity) }
                }
                .disabled(working)
            }
        }
        .formStyle(.grouped)
        .frame(maxWidth: 520)
    }

    static func message(_ reason: String) -> LocalizedStringKey {
        switch reason {
        case "UNREACHABLE": "The server could not be reached."
        case "INCOMPATIBLE": "This app cannot sign in to this server yet."
        case "CREDENTIALS": "Email or password is not right."
        case "SECRET_KEY": "The Secret Key is not right."
        case "NO_TENANT": "This server has no space at this address."
        default: "Signing in did not work."
        }
    }
}
