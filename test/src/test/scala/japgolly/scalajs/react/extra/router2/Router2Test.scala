package japgolly.scalajs.react.extra.router2

import scalaz.Equal
import org.scalajs.dom
import japgolly.scalajs.react._
import japgolly.scalajs.react.test._
import japgolly.scalajs.react.vdom.prefix_<^._
import utest._
import TestUtil._
import TestUtil2._

object Router2Test extends TestSuite {

  sealed trait Module
  case object ModuleRoot extends Module
  case object Module1 extends Module
  case class Module2(i: Int) extends Module

  object Module {
    val routes = RouterConfigDsl[Module].buildRule { dsl =>
      import dsl._

      def moduleRoot(ctl: RouterCtl[Module]) =
        <.div(
          ctl.link(Module1)("Module One"),
          ctl.link(Module2(7))("Module 2.7"))

      (emptyRule
        | staticRoute(root, ModuleRoot) ~> renderR(moduleRoot)
        | staticRoute("one", Module1) ~> render(<.h3("Module #1"))
        | dynamicRouteCT("two" / int.caseclass1(Module2)(Module2.unapply)) ~> dynRender(m => <.h3(s"Module #2 @ ${m.i}"))
        )
    }
  }

  sealed trait MyPage2
  object MyPage2 {
    case object PublicHome              extends MyPage2
    case object PrivatePage1            extends MyPage2
    case object PrivatePage2            extends MyPage2
    case class UserProfilePage(id: Int) extends MyPage2
    case class NestedModule(m: Module)  extends MyPage2
    case object SomethingElse           extends MyPage2

    case class E(n: En) extends MyPage2
    sealed trait En
    case object E1 extends En
    case object E2 extends En
    def renderE(e: E) = <.div()

    implicit val pageEq: Equal[MyPage2] = Equal.equalA

    var isUserLoggedIn: Boolean = false
    var secret = "apples"

    val userProfilePage =
      ReactComponentB[UserProfilePage]("User profile")
        .render(p => <.div(s"Hello user #${p.id}"))
        .build

    case class NavProps(curPage: MyPage2, ctl: RouterCtl[MyPage2])
    val nav = ReactComponentB[NavProps]("NavBar")
      .render { i =>
        def item(p: MyPage2, name: String) =
          if (p == i.curPage)
            <.span(name)
          else
            i.ctl.link(p)(name)
        <.div(
          item(PublicHome, "Home"),
          isUserLoggedIn ?= Seq(
            item(PrivatePage1, "Private page #1"),
            item(PrivatePage2, "Private page #2"))
        )
      }
      .build

    var innerPageEq: Equal[MyPage2] = null

    val config = RouterConfigDsl[MyPage2].buildConfig { dsl =>
      import dsl._

      innerPageEq = implicitly[Equal[MyPage2]]

      val privatePages = (emptyRule
        | dynamicRouteCT("user" / int.caseclass1(UserProfilePage)(UserProfilePage.unapply)) ~> dynRender(userProfilePage(_))
        | staticRoute("private-1", PrivatePage1) ~> render(<.h1("Private #1"))
        | staticRoute("private-2", PrivatePage2) ~> render(<.h1("Private #2: ", secret))
        )
        .addCondition(isUserLoggedIn)(page => redirectToPage(PublicHome)(Redirect.Push))

      val ePages = (emptyRule
        | staticRoute("e/1", E(E1)) ~> render(renderE(E(E1)))
        | staticRoute("e/2", E(E2)) ~> render(renderE(E(E2)))
        )

      val nestedModule =
        Module.routes.prefixPath_/("module").pmap[MyPage2](NestedModule){ case NestedModule(m) => m }

      (emptyRule // removeTrailingSlashes
        | staticRoute(root, PublicHome) ~> render(<.h1("HOME"))
        | nestedModule
        | ePages
        | privatePages
        )
        .notFound(redirectToPage(if (isUserLoggedIn) PublicHome else PrivatePage1)(Redirect.Replace))
        .renderWith((ctl, res) =>
          <.div(
            nav(NavProps(res.page, ctl)),
            res.render()))
    }
  }

  // -------------------------------------------------------------------------------------------------------------------

  implicit def str2path(s: String) = Path(s)
  def htmlFor(r: Resolution[_]) = React.renderToStaticMarkup(r.render())

  override val tests = TestSuite {
    import MyPage2._
    implicit val base = BaseUrl("file:///router2Demo/")
    val (router, lgc) = Router.componentAndLogic(base, config) // .logToConsole
    val ctl = lgc.ctl

    val sim = SimHistory(base.abs)
    val r = ReactTestUtils.renderIntoDocument(router())
    def html = r.getDOMNode().outerHTML
    isUserLoggedIn = false

    def syncNoRedirect(path: Path) = {
      sim.reset(path.abs)
      val r = sim.run(lgc syncToPath path)
      assertEq(sim.history.head, path.abs)
      r
    }

    def assertSyncRedirects(path: Path, expectTo: Path) = {
      sim.reset(path.abs)
      val r = sim.run(lgc syncToPath path)
      assertEq(sim.history.head, expectTo.abs)
      r
    }

    'addCondition {
      assertContains(html, ">Home</span>") // not at link cos current page
      assertContains(html, "Private page", false) // not logged in

      isUserLoggedIn = true
      r.forceUpdate()
      assertContains(html, ">Home</span>") // not at link cos current page
      assertContains(html, "Private page", true) // logged in

      ctl.set(PrivatePage1).unsafePerformIO()
      assertContains(html, ">Home</a>") // link cos not on current page
      assertContains(html, "Private #1")

      isUserLoggedIn = false
      ctl.refresh.unsafePerformIO()
      assertContains(html, ">Home</span>") // not at link cos current page
      assertContains(html, "Private page", false) // not logged in
    }

    'notFoundLazyRedirect {
      def r = sim.run(lgc syncToPath Path("what"))
      assertEq(r.page,  PublicHome)
      isUserLoggedIn = true
      assertEq(r.page,  PrivatePage1)
    }

    'lazyRender {
      isUserLoggedIn = true
      ctl.set(PrivatePage2).unsafePerformIO()
      assertContains(html, secret)
      secret = "oranges"
      r.forceUpdate()
      assertContains(html, secret)
    }

    'detectErrors {
      var es = config.detectErrorsE(PublicHome, PrivatePage1, PrivatePage2, UserProfilePage(1))
      assertEq(es, Vector.empty)
      es = config.detectErrorsE(SomethingElse)
      assert(es.nonEmpty)
    }

    'routesPerNestedPageType {
      assertEq("E1", ctl.pathFor(E(E1)).value, "e/1")
      assertEq("E2", ctl.pathFor(E(E2)).value, "e/2")
      val es = config.detectErrorsE(E(E1), E(E2))
      assertEq(es, Vector.empty)
    }

    'pageEquality -
      assert(innerPageEq eq pageEq)

    'nestedModule {
      'detectErrors {
        val es = config.detectErrors(NestedModule(ModuleRoot), NestedModule(Module1), NestedModule(Module2(666)))
        assertEq(es, Vector.empty)
      }
      'origPathNotAvail {
        val r = sim.run(lgc syncToPath Path("one"))
        assertEq(r.page,  PublicHome)
        assertEq(sim.history.head, Path.root.abs)
      }
      'slashNop {
        // prefixPath_/ only adds a / when rhs is empty
        assertSyncRedirects("module/", "")
        ()
      }
      'nestedStaticPath {
        val r = syncNoRedirect("module/one")
        assertEq(r.page,  NestedModule(Module1))
        assertContains(htmlFor(r), "Module #1")
      }
      'nestedDynamicPath {
        val r = syncNoRedirect("module/two/123")
        assertEq(r.page,  NestedModule(Module2(123)))
        assertContains(htmlFor(r), "Module #2 @ 123")
      }
      'routerLinks {
        assertEq(ctl.pathFor(NestedModule(ModuleRoot)).value, "module")
        assertEq(ctl.pathFor(NestedModule(Module1)).value, "module/one")
        assertEq(ctl.pathFor(NestedModule(Module2(666))).value, "module/two/666")
        ctl.set(NestedModule(ModuleRoot)).unsafePerformIO()
        assertContains(html, "/module/one\"")
        assertContains(html, "/module/two/7\"")
      }
    }
  }
}