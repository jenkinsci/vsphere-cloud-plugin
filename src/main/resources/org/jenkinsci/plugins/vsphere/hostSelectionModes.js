/*
 * DRS is that of a vCenter, and the fewest running VMs are those of standalone ESXi hosts over SSH, so the choice of
 * a host selection mode (of the cloud, and of its templates, which are in the same form) hides the one that the chosen
 * type of connection has no use for. What is chosen already is never hidden, so that a form is not changed by being
 * shown. The options are put in the list by the page after it is loaded, so they are looked at when they come.
 */
Behaviour.specify("select[name='_.hostSelectionMode']", "host-selection-modes", 0, function (select) {
  // the connection of this cloud: the nearest part of the form that has one
  let scope = select.parentElement;
  while (scope && !scope.querySelector("div[name='backend']")) {
    scope = scope.parentElement;
  }
  if (!scope) {
    return;
  }
  const esxiChosen = function () {
    const port = scope.querySelector("div[name='backend'] input[name='_.port']");
    return port !== null && port.closest("[field-disabled]") === null;
  };
  const apply = function () {
    const esxi = esxiChosen();
    Array.from(select.options).forEach(function (option) {
      const unfit = esxi ? option.value === "DRS_RECOMMENDED" : option.value === "FEWEST_RUNNING_VMS";
      option.hidden = unfit && !option.selected;
      option.disabled = option.hidden;
    });
  };
  apply();
  new MutationObserver(apply).observe(select, { childList: true });
  const watch = window.setInterval(function () {
    if (!document.body.contains(select)) {
      window.clearInterval(watch);
      return;
    }
    apply();
  }, 300);
});
