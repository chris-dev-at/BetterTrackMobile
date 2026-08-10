package at.bettertrack.iosapp

import at.bettertrack.shared.ui.BetterTrackRootViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCValues
import platform.Foundation.NSStringFromClass
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScreen
import platform.UIKit.UIWindow

/**
 * The iOS process entry point — the exact job `main.m` does in an Objective-C
 * app, written in Kotlin. Hands control to UIKit, naming [AppDelegate] as the
 * application delegate; UIKit instantiates it by class name from there.
 *
 * argv is rebuilt rather than passed as null because `UIApplicationMain` reads
 * it, and a null argv is undefined behaviour on some UIKit paths.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun main() {
    val args = arrayOf("BetterTrack")
    memScoped {
        val argv = args.map { it.cstr.ptr }.toCValues().ptr
        autoreleasepool {
            UIApplicationMain(
                argc = args.size,
                argv = argv,
                principalClassName = null,
                delegateClassName = NSStringFromClass(AppDelegate),
            )
        }
    }
}

/**
 * The whole iOS app shell. It creates one window and puts :shared's Compose
 * content in it — no storyboard, no scene delegate, no Swift.
 *
 * The `companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta`
 * line is not decoration: Kotlin/Native models Objective-C metaclasses
 * explicitly, and it is what makes [NSStringFromClass] above able to hand this
 * class to UIKit by name.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class AppDelegate : UIResponder, UIApplicationDelegateProtocol {

    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta

    // NOTE: `UIResponder` above must NOT be written `UIResponder()`. The parens
    // would declare a primary constructor, and this @OverrideInit secondary one
    // — which is what lets UIKit `alloc/init` the delegate itself — then fails
    // to compile with "Conflicting overloads: constructor(): AppDelegate".
    @OverrideInit
    constructor() : super()

    private var _window: UIWindow? = null
    override fun window(): UIWindow? = _window
    override fun setWindow(window: UIWindow?) {
        _window = window
    }

    override fun application(
        application: UIApplication,
        didFinishLaunchingWithOptions: Map<Any?, *>?,
    ): Boolean {
        _window = UIWindow(frame = UIScreen.mainScreen.bounds).apply {
            rootViewController = BetterTrackRootViewController()
            makeKeyAndVisible()
        }
        return true
    }
}
