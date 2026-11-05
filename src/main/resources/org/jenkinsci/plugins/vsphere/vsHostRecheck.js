/*
 * What the host should look like depends on the connection type that is chosen (a vCenter has an https:// URL, a
 * standalone ESXi host a plain name). The check of the host looks at the port, which only the settings of a standalone
 * ESXi host have, to tell which: it is sent when those settings are the chosen ones, and not when they are there but
 * not chosen (they are kept in the form, disabled). And the check is done again when the type is changed.
 */
Behaviour.specify("input[name='_.vsHost']", "vs-host-depends-on-type", 0, function (host) {
  const form = host.closest("form");
  if (!form) {
    return;
  }
  // the port of the chosen settings, if those are the ones of a standalone ESXi host
  const esxiChosen = function () {
    const port = form.querySelector("input[name='_.port']");
    return port !== null && port.closest("[field-disabled]") === null;
  };
  // "port" alone is not found from the host: it is in the settings of the connection type
  const dependOnType = function () {
    host.setAttribute("checkdependson", esxiChosen() ? "backend/port" : "");
  };
  dependOnType();
  // the check that the page makes by itself when it is loaded was made before this, with what the field depended on then
  window.setTimeout(function () {
    dependOnType();
    if (host.value && typeof host.onchange === "function") {
      host.onchange.call(host);
    }
  }, 200);
  let had = esxiChosen();
  const watch = window.setInterval(function () {
    if (!document.body.contains(host)) {
      window.clearInterval(watch);
      return;
    }
    const has = esxiChosen();
    if (has !== had) {
      had = has;
      dependOnType();
      // the check of a field is its onchange, which takes what it depends on as it is now
      if (host.value && typeof host.onchange === "function") {
        host.onchange.call(host);
      }
    }
  }, 300);
});
