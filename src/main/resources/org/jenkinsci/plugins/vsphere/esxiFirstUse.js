/*
 * Asks for a confirmation when "the host key seen first" is chosen as what to trust for an ESXi host: that makes
 * the configuration save itself, which is not what saving a configuration usually is about.
 */
Behaviour.specify("select.esxi-host-key-policy", "esxi-first-use-confirm", 0, function (select) {
  select.dataset.previousPolicy = select.value;
  select.addEventListener("change", function () {
    if (
      select.value === "TRUST_FIRST_USE" &&
      !window.confirm(
        "Trusting the host key that is seen first makes Jenkins save its configuration by itself, " +
          "at the first connection to the host, to remember that host key: the configuration of the folder, " +
          "for a cloud that is in a folder, otherwise the one of Jenkins.\n\nDo you want that?"
      )
    ) {
      select.value = select.dataset.previousPolicy;
      return;
    }
    select.dataset.previousPolicy = select.value;
  });
});
