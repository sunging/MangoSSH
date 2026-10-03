#include "vterm_internal.h"

#include <stdio.h>

#include "utf8.h"

/*
 * Modified keys are encoded in one of three ways:
 *
 *  - Legacy (default): what xterm sends without modifyOtherKeys. Ctrl folds a character into
 *    its C0 control code where one exists, Alt prefixes ESC, and anything without a legacy
 *    form drops the modifier. Shells and readline only understand this encoding.
 *  - xterm modifyOtherKeys (CSI > 4 ; level m): CSI 27 ; mod ; code ~ for keys whose legacy
 *    form loses information (level 1) or for every modified key (level 2).
 *  - kitty "disambiguate escape codes" (CSI > 1 u): CSI code ; mod u.
 *
 * The application chooses; kitty takes precedence when both are enabled.
 */

/* Writes one character, ESC-prefixed when Alt is held, as a single output chunk. */
static void push_legacy(VTerm *vt, uint32_t c, VTermModifier mod)
{
  char str[7];
  int seqlen = 0;
  if(mod & VTERM_MOD_ALT)
    str[seqlen++] = ESC_S[0];
  seqlen += fill_utf8(c, str + seqlen);
  vterm_push_output_bytes(vt, str, seqlen);
}

/* Returns 1 when an application-enabled protocol encoded the key. `ambiguous` says whether the
 * legacy encoding would lose one of the modifiers, which is all modifyOtherKeys level 1 covers. */
static int push_protocol_key(VTerm *vt, uint32_t code, VTermModifier mod, int ambiguous)
{
  VTermState *state = vt->state;

  if(vterm_state_kitty_keyboard_flags(state) & KITTY_KEYBOARD_DISAMBIGUATE) {
    if(mod != 0)
      vterm_push_output_sprintf_ctrl(vt, C1_CSI, "%d;%du", code, mod+1);
    else if(code == 0x1b) // Escape is the one unmodified key disambiguation changes
      vterm_push_output_sprintf_ctrl(vt, C1_CSI, "%du", code);
    else
      return 0;
    return 1;
  }

  if(mod != 0 && (state->modify_other_keys == 2 || (state->modify_other_keys == 1 && ambiguous))) {
    vterm_push_output_sprintf_ctrl(vt, C1_CSI, "27;%d;%d~", mod+1, code);
    return 1;
  }

  return 0;
}

/* The C0 control code xterm sends for Ctrl plus this character, or -1 if there is none. */
static int legacy_ctrl_code(uint32_t c)
{
  if(c >= 'a' && c <= 'z')
    return c - 'a' + 1;
  if(c >= 'A' && c <= 'Z')
    return c - 'A' + 1;

  switch(c) {
    case ' ': case '@': case '`': case '2':
      return 0x00;
    case '[': case '3':
      return 0x1b;
    case '\\': case '4':
      return 0x1c;
    case ']': case '5':
      return 0x1d;
    case '^': case '~': case '6':
      return 0x1e;
    case '_': case '-': case '/': case '7':
      return 0x1f;
    case '?': case '8':
      return 0x7f;
  }
  return -1;
}

void vterm_keyboard_unichar(VTerm *vt, uint32_t c, VTermModifier mod)
{
  /* A control character is already fully encoded (for example a ^H Backspace); only Alt
   * still applies to it. */
  if(c < 0x20 || c == 0x7f) {
    push_legacy(vt, c, mod);
    return;
  }

  /* Shift alone never changes how a character is sent: the character already reflects it. */
  if(!(mod & (VTERM_MOD_CTRL|VTERM_MOD_ALT))) {
    push_legacy(vt, c, 0);
    return;
  }

  int upper = (c >= 'A' && c <= 'Z');
  if(upper)
    mod |= VTERM_MOD_SHIFT;

  int ctrl_code = legacy_ctrl_code(c);
  int ambiguous = (mod & VTERM_MOD_CTRL) && (ctrl_code < 0 || (mod & VTERM_MOD_SHIFT));
  /* kitty reports the unshifted key with Shift as a modifier; xterm reports the character. */
  uint32_t code = c;
  if(upper && (vterm_state_kitty_keyboard_flags(vt->state) & KITTY_KEYBOARD_DISAMBIGUATE))
    code = c - 'A' + 'a';
  if(push_protocol_key(vt, code, mod, ambiguous))
    return;

  if((mod & VTERM_MOD_CTRL) && ctrl_code >= 0)
    c = ctrl_code;

  push_legacy(vt, c, mod);
}

typedef struct {
  enum {
    KEYCODE_NONE,
    KEYCODE_LITERAL,
    KEYCODE_TAB,
    KEYCODE_ENTER,
    KEYCODE_SS3,
    KEYCODE_CSI,
    KEYCODE_CSI_CURSOR,
    KEYCODE_CSINUM,
    KEYCODE_KEYPAD,
  } type;
  char literal;
  int csinum;
} keycodes_s;

static keycodes_s keycodes[] = {
  { KEYCODE_NONE }, // NONE

  { KEYCODE_ENTER,   '\r'   }, // ENTER
  { KEYCODE_TAB,     '\t'   }, // TAB
  { KEYCODE_LITERAL, '\x7f' }, // BACKSPACE == ASCII DEL
  { KEYCODE_LITERAL, '\x1b' }, // ESCAPE

  { KEYCODE_CSI_CURSOR, 'A' }, // UP
  { KEYCODE_CSI_CURSOR, 'B' }, // DOWN
  { KEYCODE_CSI_CURSOR, 'D' }, // LEFT
  { KEYCODE_CSI_CURSOR, 'C' }, // RIGHT

  { KEYCODE_CSINUM, '~', 2 },  // INS
  { KEYCODE_CSINUM, '~', 3 },  // DEL
  { KEYCODE_CSI_CURSOR, 'H' }, // HOME
  { KEYCODE_CSI_CURSOR, 'F' }, // END
  { KEYCODE_CSINUM, '~', 5 },  // PAGEUP
  { KEYCODE_CSINUM, '~', 6 },  // PAGEDOWN
};

static keycodes_s keycodes_fn[] = {
  { KEYCODE_NONE },            // F0 - shouldn't happen
  { KEYCODE_SS3,    'P' },     // F1
  { KEYCODE_SS3,    'Q' },     // F2
  { KEYCODE_SS3,    'R' },     // F3
  { KEYCODE_SS3,    'S' },     // F4
  { KEYCODE_CSINUM, '~', 15 }, // F5
  { KEYCODE_CSINUM, '~', 17 }, // F6
  { KEYCODE_CSINUM, '~', 18 }, // F7
  { KEYCODE_CSINUM, '~', 19 }, // F8
  { KEYCODE_CSINUM, '~', 20 }, // F9
  { KEYCODE_CSINUM, '~', 21 }, // F10
  { KEYCODE_CSINUM, '~', 23 }, // F11
  { KEYCODE_CSINUM, '~', 24 }, // F12
};

static keycodes_s keycodes_kp[] = {
  { KEYCODE_KEYPAD, '0', 'p' }, // KP_0
  { KEYCODE_KEYPAD, '1', 'q' }, // KP_1
  { KEYCODE_KEYPAD, '2', 'r' }, // KP_2
  { KEYCODE_KEYPAD, '3', 's' }, // KP_3
  { KEYCODE_KEYPAD, '4', 't' }, // KP_4
  { KEYCODE_KEYPAD, '5', 'u' }, // KP_5
  { KEYCODE_KEYPAD, '6', 'v' }, // KP_6
  { KEYCODE_KEYPAD, '7', 'w' }, // KP_7
  { KEYCODE_KEYPAD, '8', 'x' }, // KP_8
  { KEYCODE_KEYPAD, '9', 'y' }, // KP_9
  { KEYCODE_KEYPAD, '*', 'j' }, // KP_MULT
  { KEYCODE_KEYPAD, '+', 'k' }, // KP_PLUS
  { KEYCODE_KEYPAD, ',', 'l' }, // KP_COMMA
  { KEYCODE_KEYPAD, '-', 'm' }, // KP_MINUS
  { KEYCODE_KEYPAD, '.', 'n' }, // KP_PERIOD
  { KEYCODE_KEYPAD, '/', 'o' }, // KP_DIVIDE
  { KEYCODE_KEYPAD, '\r', 'M' }, // KP_ENTER
  { KEYCODE_KEYPAD, '=', 'X' }, // KP_EQUAL
};

void vterm_keyboard_key(VTerm *vt, VTermKey key, VTermModifier mod)
{
  if(key == VTERM_KEY_NONE)
    return;

  keycodes_s k;
  if(key < VTERM_KEY_FUNCTION_0) {
    if(key >= sizeof(keycodes)/sizeof(keycodes[0]))
      return;
    k = keycodes[key];
  }
  else if(key >= VTERM_KEY_FUNCTION_0 && key <= VTERM_KEY_FUNCTION_MAX) {
    if((key - VTERM_KEY_FUNCTION_0) >= sizeof(keycodes_fn)/sizeof(keycodes_fn[0]))
      return;
    k = keycodes_fn[key - VTERM_KEY_FUNCTION_0];
  }
  else if(key >= VTERM_KEY_KP_0) {
    if((key - VTERM_KEY_KP_0) >= sizeof(keycodes_kp)/sizeof(keycodes_kp[0]))
      return;
    k = keycodes_kp[key - VTERM_KEY_KP_0];
  }

  switch(k.type) {
  case KEYCODE_NONE:
    break;

  case KEYCODE_TAB:
    /* Shift-Tab is CSI Z but plain Tab is 0x09; modifyOtherKeys leaves Shift-Tab alone */
    if(mod == VTERM_MOD_SHIFT &&
        !(vterm_state_kitty_keyboard_flags(vt->state) & KITTY_KEYBOARD_DISAMBIGUATE))
      vterm_push_output_sprintf_ctrl(vt, C1_CSI, "Z");
    else if(push_protocol_key(vt, k.literal, mod, mod & VTERM_MOD_CTRL))
      break;
    else if(mod & VTERM_MOD_SHIFT)
      vterm_push_output_sprintf_ctrl(vt, C1_CSI, "1;%dZ", mod+1);
    else
      goto case_LITERAL;
    break;

  case KEYCODE_ENTER:
    if(push_protocol_key(vt, k.literal, mod, mod & (VTERM_MOD_SHIFT|VTERM_MOD_CTRL)))
      break;
    /* Enter is CRLF in newline mode, but just CR in linefeed mode */
    if(vt->state->mode.newline)
      vterm_push_output_sprintf(vt, mod & VTERM_MOD_ALT ? ESC_S "\r\n" : "\r\n");
    else
      goto case_LITERAL;
    break;

  case KEYCODE_LITERAL: // Backspace and Escape
    if(push_protocol_key(vt, (unsigned char)k.literal, mod,
          mod & (k.literal == 0x7f ? VTERM_MOD_SHIFT : (VTERM_MOD_SHIFT|VTERM_MOD_CTRL))))
      break;
    /* Ctrl-Backspace is ^H, as in xterm and VTE */
    if(k.literal == 0x7f && (mod & VTERM_MOD_CTRL))
      k.literal = 0x08;
    goto case_LITERAL;

  case_LITERAL:
    /* Legacy keys only carry Alt, as an ESC prefix */
    push_legacy(vt, (unsigned char)k.literal, mod);
    break;

  case KEYCODE_SS3: case_SS3:
    if(mod == 0)
      vterm_push_output_sprintf_ctrl(vt, C1_SS3, "%c", k.literal);
    else
      goto case_CSI;
    break;

  case KEYCODE_CSI: case_CSI:
    if(mod == 0)
      vterm_push_output_sprintf_ctrl(vt, C1_CSI, "%c", k.literal);
    else
      vterm_push_output_sprintf_ctrl(vt, C1_CSI, "1;%d%c", mod + 1, k.literal);
    break;

  case KEYCODE_CSINUM:
    if(mod == 0)
      vterm_push_output_sprintf_ctrl(vt, C1_CSI, "%d%c", k.csinum, k.literal);
    else
      vterm_push_output_sprintf_ctrl(vt, C1_CSI, "%d;%d%c", k.csinum, mod + 1, k.literal);
    break;

  case KEYCODE_CSI_CURSOR:
    if(vt->state->mode.cursor)
      goto case_SS3;
    else
      goto case_CSI;

  case KEYCODE_KEYPAD:
    if(vt->state->mode.keypad) {
      k.literal = k.csinum;
      goto case_SS3;
    }
    else
      goto case_LITERAL;
  }
}

void vterm_keyboard_start_paste(VTerm *vt)
{
  if(vt->state->mode.bracketpaste)
    vterm_push_output_sprintf_ctrl(vt, C1_CSI, "200~");
}

void vterm_keyboard_end_paste(VTerm *vt)
{
  if(vt->state->mode.bracketpaste)
    vterm_push_output_sprintf_ctrl(vt, C1_CSI, "201~");
}
