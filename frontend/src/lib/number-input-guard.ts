// Stops the mouse wheel from changing a focused type="number" input: the input loses focus on wheel,
// so the page scrolls instead of the value changing. Most forms use digit-only text inputs instead;
// this covers any number input that remains (for example in the sleep questionnaire).
document.addEventListener(
  "wheel",
  (event) => {
    const active = document.activeElement;
    if (active instanceof HTMLInputElement && active.type === "number" && event.target === active) {
      active.blur();
    }
  },
  { passive: true },
);

export {};
