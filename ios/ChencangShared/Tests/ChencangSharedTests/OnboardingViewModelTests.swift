import XCTest
@testable import ChencangShared

@MainActor
final class OnboardingViewModelTests: XCTestCase {
    func testReachesComplete() async {
        let store = IdentityStore(keychain: FakeKeychain())
        let vm = OnboardingViewModel(identityStore: store, namePromptDone: { true }, saveName: { _ in }, acceptTerms: {})
        XCTAssertEqual(vm.state, .welcome)
        vm.termsChecked = true
        await vm.createIdentity()
        XCTAssertEqual(vm.state, .complete)
        XCTAssertNil(vm.errorMessage)
    }

    private func makeVM(done: Bool, saved: @escaping (String?) -> Void = { _ in }) -> OnboardingViewModel {
        OnboardingViewModel(
            identityStore: IdentityStore(keychain: FakeKeychain()),
            namePromptDone: { done }, saveName: saved, acceptTerms: {})
    }

    func testUncheckedTermsBlocksIdentityCreation() async {
        let store = IdentityStore(keychain: FakeKeychain())
        var accepted = 0
        let vm = OnboardingViewModel(identityStore: store, namePromptDone: { true }, saveName: { _ in }, acceptTerms: { accepted += 1 })
        await vm.createIdentity()
        XCTAssertEqual(vm.state, .welcome)
        XCTAssertFalse(store.hasPersistedIdentity())
        XCTAssertEqual(accepted, 0)
        vm.termsChecked = true
        await vm.createIdentity()
        XCTAssertEqual(vm.state, .complete)
        XCTAssertTrue(store.hasPersistedIdentity())
        XCTAssertEqual(accepted, 1)
    }

    func testActAfterIdentityAsksNameUnlessAlreadyAnswered() {
        XCTAssertEqual(makeVM(done: false).actAfterIdentity(), .name)
        XCTAssertEqual(makeVM(done: true).actAfterIdentity(), .mechanism)
    }

    func testNameContinueNormalisesSavesAndAdvances() {
        var saved: [String?] = []
        let vm = makeVM(done: false) { saved.append($0) }
        XCTAssertEqual(vm.answerName("  阿青 "), .mechanism)
        XCTAssertEqual(saved, ["阿青"])
    }

    func testNameSkipAndBlankSaveNilAndAdvance() {
        var saved: [String?] = []
        let vm = makeVM(done: false) { saved.append($0) }
        XCTAssertEqual(vm.answerName(nil), .mechanism)
        XCTAssertEqual(vm.answerName("   "), .mechanism)
        XCTAssertEqual(saved, [nil, nil])
    }

    func testActRawValuesRoundTripForSceneStorage() {
        for act in [OnboardingViewModel.Act.brand, .name, .mechanism] {
            XCTAssertEqual(OnboardingViewModel.Act(rawValue: act.rawValue), act)
        }
        XCTAssertNil(OnboardingViewModel.Act(rawValue: "garbage"))
    }

    func testStaleMechanismWithoutIdentityResolvesToBrand() {
        XCTAssertEqual(OnboardingViewModel.resolveAct(stored: "mechanism", identityExists: false), .brand)
        XCTAssertEqual(OnboardingViewModel.resolveAct(stored: "name", identityExists: false), .brand)
    }

    func testGarbageOrMissingStoredActResolvesToBrand() {
        XCTAssertEqual(OnboardingViewModel.resolveAct(stored: "garbage", identityExists: true), .brand)
        XCTAssertEqual(OnboardingViewModel.resolveAct(stored: nil, identityExists: true), .brand)
    }

    func testIdentityPresentKeepsStoredAct() {
        XCTAssertEqual(OnboardingViewModel.resolveAct(stored: "name", identityExists: true), .name)
        XCTAssertEqual(OnboardingViewModel.resolveAct(stored: "mechanism", identityExists: true), .mechanism)
    }

    func testRestoredActUsesIdentityStoreExistence() async {
        let vm = makeVM(done: false)
        XCTAssertEqual(vm.restoredAct(stored: "mechanism"), .brand)
    }
}
