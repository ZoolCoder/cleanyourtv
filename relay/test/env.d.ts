declare namespace Cloudflare {
  interface GlobalProps {
    mainModule: typeof import("../src/index");
  }
  interface Env {
    BOX: DurableObjectNamespace<import("../src/box").Box>;
    LIMITER: DurableObjectNamespace<import("../src/box").Limiter>;
  }
}
