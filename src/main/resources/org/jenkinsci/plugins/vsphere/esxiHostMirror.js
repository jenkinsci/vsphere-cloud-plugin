/*
 * A host of a cluster that does not say its port, its credentials or how its fingerprint is trusted has those of the first
 * host. The buttons of the form (Show fingerprint, Test Connection) can only see the fields next to them, not those of
 * the first host, so each host has hidden fields (class esxi-mirror) that copy them, for the buttons to send.
 */
Behaviour.specify("input.esxi-mirror", "esxi-mirror", 0, function (mirror) {
  const group = mirror.closest("div[name='backend']");
  if (!group) {
    return;
  }
  // the field of the first host is the one that is not in a chunk of the hosts that are added
  const source = Array.from(group.querySelectorAll("[name='_." + mirror.dataset.source + "']")).find(function (field) {
    return field.closest(".repeated-chunk") === null;
  });
  if (!source) {
    return;
  }
  const copy = function () {
    mirror.value = source.value;
  };
  copy();
  source.addEventListener("change", copy);
  source.addEventListener("input", copy);
});
