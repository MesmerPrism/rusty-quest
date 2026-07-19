// AGPL-3.0-or-later. Ported with permission from Trevor Hewitt's vr_strobe.
// Source authority: https://github.com/trevorhewitt/vr_strobe at the pinned commit below.

import { createPattern, createTemporalProfile } from "./profile-contract.mjs";

export const TREVOR_SOURCE_REPOSITORY = "https://github.com/trevorhewitt/vr_strobe";
export const TREVOR_SOURCE_COMMIT = "52c71cc069f4102bc4148e05c5fd3fc4d5466479";
export const TREVOR_CATALOG_REVISION = 1;

const INTERFERENCE_SOURCES = Object.freeze([
  {
    "id": "source-sim-7hz-red",
    "title": "Simulated 7 Hz Closed Eye Lucia",
    "payload": "eyJjIjp7ImNvbG9yQ291bnQiOjIsImNvbDEiOiIjMDAwMDAwIiwiY29sMiI6IiNmZjAwMDAiLCJjb2wzIjoiIzAwYWFmZiJ9LCJhIjp7Im9zY0FjdGl2ZSI6MSwib3NjRnJlcSI6MzMuNzUsIm9zY1NoYXBlIjoyLjF9LCJnIjp7InNjYWxlIjoyNC45LCJzaGVhclgiOjAsInNoZWFyWSI6MCwib2Zmc2V0WCI6MC42OTcsIm9mZnNldFkiOi0wLjY3Miwic2hha2VBbXAiOjAuMDA2LCJzaGFrZUZyZXEiOjUwLCJyb3RTcGVlZCI6MS4zNSwic3RlcEZhY3RvciI6MC4xOTF9LCJwIjp7InRyYWlsQW1vdW50IjowLjU3LCJibHVyUmFkaXVzIjo5LjIsImdsb3dTdHJlbmd0aCI6MCwiYnJpZ2h0bmVzcyI6LTAuMDUsImNvbnRyYXN0IjoxLjA1fSwiZSI6eyJub2lzZUZyZXEiOjQuNTk4LCJub2lzZVN0cmVuZ3RoIjowLjU2LCJub2lzZUJpYXMiOjAuNSwidmlnQ2VudGVyIjo1LCJ2aWdFZGdlIjo1LCJ2aWdCaWFzIjowLjE4Nn0sInMiOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOjAuOCwicGVyaW9kIjozLjIsInNwZWVkIjoxLCJwaXZvdFgiOjAsInBpdm90WSI6MCwiZGlzdG9ydEZyZXEiOjAsImRpc3RvcnRBbXAiOjAsImRpc3RvcnRTcGVlZCI6MSwiZGlzdE11bHRQYXIiOjEsImRpc3RNdWx0T3J0aCI6MSwid2F2ZUZyZXEiOjEuMiwid2F2ZUFtcCI6MC4wOCwid2F2ZVNoYXBlIjoxLCJhbmdsZSI6My4wMDIsInJvdFNwZWVkIjotMC40OTksImV4dGVudCI6MH1dLCJyIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjowLjgsInBlcmlvZCI6My4yLCJzcGVlZCI6MSwicGl2b3RYIjowLCJwaXZvdFkiOjAsImRpc3RvcnRGcmVxIjowLCJkaXN0b3J0QW1wIjowLCJkaXN0b3J0U3BlZWQiOjEsImRpc3RNdWx0UGFyIjoxLCJkaXN0TXVsdE9ydGgiOjEsIndhdmVGcmVxIjoxLjIsIndhdmVBbXAiOjAuMDgsIndhdmVTaGFwZSI6MSwicm90UGl2WCI6LTAuNywicm90UGl2WSI6LTEuMzU3LCJyb3RTcGVlZCI6MS4xNiwibm9pc2VNb3ZlIjoxLjIxfV0sInkiOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOjAuOCwicGVyaW9kIjo1MCwic3BlZWQiOjEsInBpdm90WCI6MCwicGl2b3RZIjowLCJkaXN0b3J0RnJlcSI6MCwiZGlzdG9ydEFtcCI6MCwiZGlzdG9ydFNwZWVkIjoxLCJkaXN0TXVsdFBhciI6MSwiZGlzdE11bHRPcnRoIjoxLCJ3YXZlRnJlcSI6MS4yLCJ3YXZlQW1wIjowLjA4LCJ3YXZlU2hhcGUiOjEsInJvdFBpdlgiOi0wLjU5LCJyb3RQaXZZIjotMC4wMiwicm90U3BlZWQiOjAsIm5vaXNlTW92ZSI6MH1dLCJuIjpbXX0="
  },
  {
    "id": "source-sim-40hz-pale",
    "title": "Simulated 40 Hz Open Eye Roxiva",
    "payload": "eyJjIjp7ImNvbG9yQ291bnQiOjMsImNvbDEiOiIjMDAwMDAwIiwiY29sMiI6IiNkYmY4ZmYiLCJjb2wzIjoiI2ZmZmZmZiJ9LCJhIjp7Im9zY0FjdGl2ZSI6MSwib3NjRnJlcSI6NDAsIm9zY1NoYXBlIjozLjJ9LCJnIjp7InNjYWxlIjoxMC43LCJzaGVhclgiOjAsInNoZWFyWSI6MC4wMiwib2Zmc2V0WCI6MCwib2Zmc2V0WSI6MCwic2hha2VBbXAiOjAuMDEsInNoYWtlRnJlcSI6NDAsInJvdFNwZWVkIjowLjIxLCJzdGVwRmFjdG9yIjowLjE5MX0sInAiOnsidHJhaWxBbW91bnQiOjAuNjcsImJsdXJSYWRpdXMiOjIuOCwiZ2xvd1N0cmVuZ3RoIjowLCJicmlnaHRuZXNzIjowLjM0LCJjb250cmFzdCI6MC42Mn0sImUiOnsibm9pc2VGcmVxIjoyLjgsIm5vaXNlU3RyZW5ndGgiOjAuMzIsIm5vaXNlQmlhcyI6MC41LCJ2aWdDZW50ZXIiOjAuMDUsInZpZ0VkZ2UiOjEuODMsInZpZ0JpYXMiOjB9LCJzIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjotMC43LCJwZXJpb2QiOjIxLCJzcGVlZCI6Ny41OTIsImFuZ2xlIjozLjAwMiwicm90U3BlZWQiOi0wLjQ5OSwicGl2b3RYIjotMS45NDQsInBpdm90WSI6MC4zOTYsImV4dGVudCI6OX0seyJhY3RpdmUiOjEsInN0cmVuZ3RoIjoxLjMsInBlcmlvZCI6MTMuMiwic3BlZWQiOjIsImFuZ2xlIjowLCJyb3RTcGVlZCI6MCwicGl2b3RYIjotMC44LCJwaXZvdFkiOi0xLjIyLCJleHRlbnQiOjB9XSwiciI6W3siYWN0aXZlIjoxLCJzdHJlbmd0aCI6MC40LCJwZXJpb2QiOjEyLjgsInNwZWVkIjo2LjIsInBpdm90WCI6LTAuMDIsInBpdm90WSI6LTEuMTQ2LCJyb3RQaXZYIjotMC43LCJyb3RQaXZZIjotMS4zNTcsInJvdFNwZWVkIjoxLjE2LCJub2lzZU1vdmUiOjEuMjF9XSwieSI6W3siYWN0aXZlIjoxLCJzdHJlbmd0aCI6MC44LCJwZXJpb2QiOjEwNSwic3BlZWQiOjEsInBpdm90WCI6MCwicGl2b3RZIjowLCJyb3RQaXZYIjowLCJyb3RQaXZZIjowLCJyb3RTcGVlZCI6MCwibm9pc2VNb3ZlIjowfV19"
  },
  {
    "id": "source-sim-red",
    "title": "Simulated red",
    "payload": "eyJjIjp7ImNvbG9yQ291bnQiOjIsImNvbDEiOiIjMDAwMDAwIiwiY29sMiI6IiNmZjAwMDAiLCJjb2wzIjoiIzAwYWFmZiJ9LCJhIjp7Im9zY0FjdGl2ZSI6MSwib3NjRnJlcSI6NDAsIm9zY1NoYXBlIjowLjF9LCJnIjp7InNjYWxlIjo0OC4xLCJzaGVhclgiOjAsInNoZWFyWSI6MCwib2Zmc2V0WCI6MCwib2Zmc2V0WSI6MCwic2hha2VBbXAiOjAsInNoYWtlRnJlcSI6NDAsInJvdFNwZWVkIjowLjMyLCJzdGVwRmFjdG9yIjowfSwicCI6eyJ0cmFpbEFtb3VudCI6MCwiYmx1clJhZGl1cyI6MCwiZ2xvd1N0cmVuZ3RoIjowLCJicmlnaHRuZXNzIjowLCJjb250cmFzdCI6MC43Mn0sImUiOnsibm9pc2VGcmVxIjoxLCJub2lzZVN0cmVuZ3RoIjowLCJub2lzZUJpYXMiOjAuNSwidmlnQ2VudGVyIjowLCJ2aWdFZGdlIjoxLjkxLCJ2aWdCaWFzIjowfSwicyI6W3siYWN0aXZlIjoxLCJzdHJlbmd0aCI6MC44LCJwZXJpb2QiOjEwLCJzcGVlZCI6LTEuMywicGl2b3RYIjowLCJwaXZvdFkiOjAsImRpc3RvcnRGcmVxIjoxLCJkaXN0b3J0QW1wIjoxLjY4LCJkaXN0b3J0U3BlZWQiOjEsImRpc3RNdWx0UGFyIjowLjQsImRpc3RNdWx0T3J0aCI6MSwid2F2ZUZyZXEiOjYuOCwid2F2ZUFtcCI6My45Nywid2F2ZVNoYXBlIjowLCJhbmdsZSI6MCwicm90U3BlZWQiOjEuMTgsImV4dGVudCI6OH0seyJhY3RpdmUiOjEsInN0cmVuZ3RoIjowLjgsInBlcmlvZCI6MTAsInNwZWVkIjotMS4zLCJwaXZvdFgiOjAsInBpdm90WSI6MCwiZGlzdG9ydEZyZXEiOjEsImRpc3RvcnRBbXAiOjEuNjgsImRpc3RvcnRTcGVlZCI6MSwiZGlzdE11bHRQYXIiOjAuNCwiZGlzdE11bHRPcnRoIjoxLCJ3YXZlRnJlcSI6Ni44LCJ3YXZlQW1wIjozLjk3LCJ3YXZlU2hhcGUiOjAsImFuZ2xlIjowLCJyb3RTcGVlZCI6MC40MSwiZXh0ZW50IjoxNH1dLCJyIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjowLjgsInBlcmlvZCI6MTAsInNwZWVkIjotMS4zLCJwaXZvdFgiOjAsInBpdm90WSI6MCwiZGlzdG9ydEZyZXEiOjEsImRpc3RvcnRBbXAiOjEuNjgsImRpc3RvcnRTcGVlZCI6MSwiZGlzdE11bHRQYXIiOjAuNCwiZGlzdE11bHRPcnRoIjoxLCJ3YXZlRnJlcSI6Ni44LCJ3YXZlQW1wIjozLjk3LCJ3YXZlU2hhcGUiOjAsInJvdFBpdlgiOjAsInJvdFBpdlkiOjAsInJvdFNwZWVkIjotMC40OCwibm9pc2VNb3ZlIjowfV0sInkiOltdLCJuIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjoyLCJzY2FsZSI6Ni4zLCJ6U3BlZWQiOjEwLCJ6T2Zmc2V0IjotODIuMSwicGl2b3RYIjotMC40OSwicGl2b3RZIjowfV19"
  },
  {
    "id": "source-sim-blorbs",
    "title": "Simulated blorbs",
    "payload": "eyJjIjp7ImNvbG9yQ291bnQiOjIsImNvbDEiOiIjMDAwMDAwIiwiY29sMiI6IiNmZjAwMDAiLCJjb2wzIjoiIzAwYWFmZiJ9LCJhIjp7Im9zY0FjdGl2ZSI6MCwib3NjRnJlcSI6MTYuNDg4LCJvc2NTaGFwZSI6Ny44MjJ9LCJnIjp7InNjYWxlIjowLjY2Miwic2hlYXJYIjowLCJzaGVhclkiOjAsIm9mZnNldFgiOi0wLjcwMiwib2Zmc2V0WSI6LTAuODgxLCJzaGFrZUFtcCI6MCwic2hha2VGcmVxIjoxMi41MzksInJvdFNwZWVkIjoyLjAyNSwic3RlcEZhY3RvciI6MC43OTN9LCJwIjp7InRyYWlsQW1vdW50IjowLjI4OSwiYmx1clJhZGl1cyI6MCwiZ2xvd1N0cmVuZ3RoIjowLjA0NSwiYnJpZ2h0bmVzcyI6LTAuNTI0LCJjb250cmFzdCI6MC43NDd9LCJlIjp7Im5vaXNlRnJlcSI6MS44MTEsIm5vaXNlU3RyZW5ndGgiOjAuOTU2LCJub2lzZUJpYXMiOjAuMjM0LCJ2aWdDZW50ZXIiOjAuNDIyLCJ2aWdFZGdlIjo1LCJ2aWdCaWFzIjowLjM1fSwicyI6W3siYWN0aXZlIjoxLCJzdHJlbmd0aCI6LTAuNjQxLCJwZXJpb2QiOjMwLjYxNiwic3BlZWQiOi0wLjkxLCJwaXZvdFgiOjEuNzYxLCJwaXZvdFkiOi0xLjM1OCwiZGlzdG9ydEZyZXEiOjE3LjcwNiwiZGlzdG9ydEFtcCI6MCwiZGlzdG9ydFNwZWVkIjotOC4yNywiZGlzdE11bHRQYXIiOjMuMjcyLCJkaXN0TXVsdE9ydGgiOjQuMDQzLCJ3YXZlRnJlcSI6MTIuMDYyLCJ3YXZlQW1wIjowLjY4OCwid2F2ZVNoYXBlIjowLjk4NSwiYW5nbGUiOjAuNzgzLCJyb3RTcGVlZCI6LTEuMTA2LCJleHRlbnQiOjB9LHsiYWN0aXZlIjoxLCJzdHJlbmd0aCI6LTAuNjQxLCJwZXJpb2QiOjMwLjYxNiwic3BlZWQiOi0wLjkxLCJwaXZvdFgiOjEuNzYxLCJwaXZvdFkiOi0xLjM1OCwiZGlzdG9ydEZyZXEiOjE3LjcwNiwiZGlzdG9ydEFtcCI6MCwiZGlzdG9ydFNwZWVkIjotOC4yNywiZGlzdE11bHRQYXIiOjMuMjcyLCJkaXN0TXVsdE9ydGgiOjQuMDQzLCJ3YXZlRnJlcSI6MTIuMDYyLCJ3YXZlQW1wIjowLjY4OCwid2F2ZVNoYXBlIjowLjk4NSwiYW5nbGUiOjEuNDgyLCJyb3RTcGVlZCI6MC4yNDgsImV4dGVudCI6MH1dLCJyIjpbeyJhY3RpdmUiOjEsInN0cmVuZ3RoIjotMC42NDEsInBlcmlvZCI6MzAuNjE2LCJzcGVlZCI6LTAuOTEsInBpdm90WCI6MS43NjEsInBpdm90WSI6LTEuMzU4LCJkaXN0b3J0RnJlcSI6MTcuNzA2LCJkaXN0b3J0QW1wIjowLCJkaXN0b3J0U3BlZWQiOi04LjI3LCJkaXN0TXVsdFBhciI6My4yNzIsImRpc3RNdWx0T3J0aCI6NC4wNDMsIndhdmVGcmVxIjoxMi4wNjIsIndhdmVBbXAiOjAuNjg4LCJ3YXZlU2hhcGUiOjAuOTg1LCJyb3RQaXZYIjoxLjM4Mywicm90UGl2WSI6MS41NDQsInJvdFNwZWVkIjotMC40NzksIm5vaXNlTW92ZSI6MS4zMTV9XSwieSI6W10sIm4iOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOjEuODEzLCJzY2FsZSI6MjQuNzIzLCJ6U3BlZWQiOjcuMjM1LCJ6T2Zmc2V0IjotNDIuNTYyLCJwaXZvdFgiOjEuMTA3LCJwaXZvdFkiOjAuNjA0fV19"
  },
  {
    "id": "source-sim-shlorgs",
    "title": "Simulated shlorgs",
    "payload": "eyJjIjp7ImNvbG9yQ291bnQiOjMsImNvbDEiOiIjYjViNWI1IiwiY29sMiI6IiNmZjAwMDAiLCJjb2wzIjoiIzAwMDU5NCJ9LCJhIjp7Im9zY0FjdGl2ZSI6MSwib3NjRnJlcSI6MjUuOTksIm9zY1NoYXBlIjo0LjJ9LCJnIjp7InNjYWxlIjo0LjE3OSwic2hlYXJYIjowLCJzaGVhclkiOjAsIm9mZnNldFgiOjAuMDU5LCJvZmZzZXRZIjowLjM2NSwic2hha2VBbXAiOjAsInNoYWtlRnJlcSI6OC4yNDMsInJvdFNwZWVkIjoxLjY2OSwic3RlcEZhY3RvciI6MC4zMDR9LCJwIjp7InRyYWlsQW1vdW50IjowLCJibHVyUmFkaXVzIjowLCJnbG93U3RyZW5ndGgiOjAsImJyaWdodG5lc3MiOi0wLjI3NywiY29udHJhc3QiOjAuNDk3fSwiZSI6eyJub2lzZUZyZXEiOjEuNjQ3LCJub2lzZVN0cmVuZ3RoIjowLjg1Miwibm9pc2VCaWFzIjowLjE0OSwidmlnQ2VudGVyIjo1LCJ2aWdFZGdlIjo1LCJ2aWdCaWFzIjowLjY4N30sInMiOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOi0xLjcyOSwicGVyaW9kIjozLjU0NCwic3BlZWQiOjcuMzA0LCJwaXZvdFgiOi0xLjc5LCJwaXZvdFkiOjEuNjQ5LCJkaXN0b3J0RnJlcSI6Mi4yODEsImRpc3RvcnRBbXAiOjAsImRpc3RvcnRTcGVlZCI6LTUuNTEsImRpc3RNdWx0UGFyIjozLjAzNCwiZGlzdE11bHRPcnRoIjoxLjAzNywid2F2ZUZyZXEiOjE5LjY0OCwid2F2ZUFtcCI6MS45MTQsIndhdmVTaGFwZSI6MC4zMDksImFuZ2xlIjoyLjc0MSwicm90U3BlZWQiOjEuMDkxLCJleHRlbnQiOjEwfSx7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOi0xLjcyOSwicGVyaW9kIjozLjU0NCwic3BlZWQiOjcuMzA0LCJwaXZvdFgiOi0xLjc5LCJwaXZvdFkiOjEuNjQ5LCJkaXN0b3J0RnJlcSI6Mi4yODEsImRpc3RvcnRBbXAiOjAsImRpc3RvcnRTcGVlZCI6LTUuNTEsImRpc3RNdWx0UGFyIjozLjAzNCwiZGlzdE11bHRPcnRoIjoxLjAzNywid2F2ZUZyZXEiOjE5LjY0OCwid2F2ZUFtcCI6MS45MTQsIndhdmVTaGFwZSI6MC4zMDksImFuZ2xlIjo1LjUyNiwicm90U3BlZWQiOi0xLjQzMSwiZXh0ZW50IjowfV0sInIiOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOi0xLjcyOSwicGVyaW9kIjozLjU0NCwic3BlZWQiOjcuMzA0LCJwaXZvdFgiOi0xLjc5LCJwaXZvdFkiOjEuNjQ5LCJkaXN0b3J0RnJlcSI6Mi4yODEsImRpc3RvcnRBbXAiOjAsImRpc3RvcnRTcGVlZCI6LTUuNTEsImRpc3RNdWx0UGFyIjozLjAzNCwiZGlzdE11bHRPcnRoIjoxLjAzNywid2F2ZUZyZXEiOjE5LjY0OCwid2F2ZUFtcCI6MS45MTQsIndhdmVTaGFwZSI6MC4zMDksInJvdFBpdlgiOi0wLjI5OCwicm90UGl2WSI6MS44MDUsInJvdFNwZWVkIjotMS40ODEsIm5vaXNlTW92ZSI6MC4yNjh9XSwieSI6W10sIm4iOlt7ImFjdGl2ZSI6MSwic3RyZW5ndGgiOjEuMzc5LCJzY2FsZSI6NC44MTQsInpTcGVlZCI6OC4xODQsInpPZmZzZXQiOjAuNjM5LCJwaXZvdFgiOjEuMzU2LCJwaXZvdFkiOi0xLjE4M31dfQ=="
  }
]);

const TEMPORAL_SOURCES = Object.freeze([
  { id: "source-strobe-7hz", title: "Real 7 Hz Black & White Strobe", frequency_hz: 7 },
  {
    id: "source-strobe-14hz-noise",
    title: "Real 14 Hz Noistrobe",
    frequency_hz: 14,
    noise_phase_1: true,
    noise_amplitude_1: 1,
    fixation_enabled: true,
  },
  { id: "source-strobe-20hz", title: "Real 20 Hz Black & White Strobe", frequency_hz: 20 },
  {
    id: "source-strobe-12hz-red",
    title: "Real 12 Hz Red Strobe",
    frequency_hz: 12,
    color_2: "#ff0000",
  },
]);

const SOURCE_CREATED_AT_EPOCH_MS = 0;
const clamp = (value, min, max) => Math.min(max, Math.max(min, Number(value)));
const value = (record, key, fallback) => record && key in record ? record[key] : fallback;
const flag = (record, key, fallback = false) => {
  const candidate = value(record, key, fallback);
  return typeof candidate === "number" ? candidate !== 0 : Boolean(candidate);
};

function decodeSourcePayload(payload) {
  return JSON.parse(atob(payload));
}

function sourcePattern(raw, kind) {
  const pattern = createPattern(kind);
  return {
    ...pattern,
    active: flag(raw, "active", true),
    strength: clamp(value(raw, "strength", 1), -2, 2),
    period: clamp(value(raw, "period", 10), kind === "ray" ? 1 : 0.1, 50),
    speed: clamp(value(raw, "speed", 2), -10, 10),
    pivot_x: clamp(value(raw, "pivotX", 0), -2, 2),
    pivot_y: clamp(value(raw, "pivotY", 0), -2, 2),
    distort_freq: clamp(value(raw, "distortFreq", 1), 0, 20),
    distort_amp: clamp(value(raw, "distortAmp", 0), 0, 5),
    distort_speed: clamp(value(raw, "distortSpeed", 1), -10, 10),
    dist_mult_parallel: clamp(value(raw, "distMultPar", 1), 0, 5),
    dist_mult_orthogonal: clamp(value(raw, "distMultOrth", 1), 0, 5),
    wave_freq: clamp(value(raw, "waveFreq", 2), 0, 20),
    wave_amp: clamp(value(raw, "waveAmp", 0), 0, 5),
    wave_shape: clamp(value(raw, "waveShape", 0), 0, 1),
    angle: clamp(value(raw, "angle", 0), 0, 6.28),
    rotation_pivot_x: clamp(value(raw, "rotPivX", 0), -2, 2),
    rotation_pivot_y: clamp(value(raw, "rotPivY", 0), -2, 2),
    rotation_speed: clamp(value(raw, "rotSpeed", 0), -2, 2),
    extent: clamp(value(raw, "extent", 0), 0, 20),
    noise_move: clamp(value(raw, "noiseMove", 0), 0, 2),
    perlin_scale: clamp(value(raw, "scale", 5), 0.1, 50),
    perlin_z_speed: clamp(value(raw, "zSpeed", 1), -10, 10),
    perlin_z_offset: clamp(value(raw, "zOffset", 0), -100, 100),
  };
}

function interferenceSource(row) {
  const root = decodeSourcePayload(row.payload);
  const colors = root.c ?? {}, animation = root.a ?? {}, global = root.g ?? {};
  const post = root.p ?? {}, effects = root.e ?? {};
  const patterns = [
    ...(root.s ?? []).slice(0, 8).map(raw => sourcePattern(raw, "stripe")),
    ...(root.r ?? []).slice(0, 8).map(raw => sourcePattern(raw, "ripple")),
    ...(root.y ?? []).slice(0, 8).map(raw => sourcePattern(raw, "ray")),
    ...(root.n ?? []).slice(0, 8).map(raw => sourcePattern(raw, "perlin")),
  ];
  return {
    id: row.id,
    title: row.title,
    created_at_epoch_ms: SOURCE_CREATED_AT_EPOCH_MS,
    distance_meters: 4,
    carrier: { curved_mode: true, concavity: 1 },
    kind: "interference",
    profile: {
      id: row.id,
      title: row.title,
      source_label: row.title,
      source_payload: row.payload,
      duration_seconds: 15,
      color_count: clamp(value(colors, "colorCount", 2), 2, 3),
      color_1: value(colors, "col1", "#000000"),
      color_2: value(colors, "col2", "#ff0000"),
      color_3: value(colors, "col3", "#00aaff"),
      oscillator_active: flag(animation, "oscActive"),
      oscillator_frequency_hz: clamp(value(animation, "oscFreq", 0.5), 0, 40),
      oscillator_shape: clamp(value(animation, "oscShape", 1), 0.1, 10),
      scale: clamp(value(global, "scale", 2), 0.1, 100),
      shear_x: clamp(value(global, "shearX", 0), -2, 2),
      shear_y: clamp(value(global, "shearY", 0), -2, 2),
      offset_x: clamp(value(global, "offsetX", 0), -1, 1),
      offset_y: clamp(value(global, "offsetY", 0), -1, 1),
      shake_amplitude: clamp(value(global, "shakeAmp", 0), 0, 0.1),
      shake_frequency_hz: clamp(value(global, "shakeFreq", 5), 0, 40),
      rotation_speed: clamp(value(global, "rotSpeed", 0), -5, 5),
      step_factor: clamp(value(global, "stepFactor", 0), 0, 1),
      trail_amount: clamp(value(post, "trailAmount", 0), 0, 0.99),
      blur_radius: clamp(value(post, "blurRadius", 0), 0, 15),
      glow_strength: clamp(value(post, "glowStrength", 0), 0, 3),
      brightness: clamp(value(post, "brightness", 0), -1, 1),
      contrast: clamp(value(post, "contrast", 1), 0, 3),
      noise_frequency: clamp(value(effects, "noiseFreq", 1), 0.1, 5),
      noise_strength: clamp(value(effects, "noiseStrength", 0), 0, 1),
      noise_bias: clamp(value(effects, "noiseBias", 0.5), 0, 1),
      vignette_center: clamp(value(effects, "vigCenter", 0), 0, 5),
      vignette_edge: clamp(value(effects, "vigEdge", 2), 0, 5),
      vignette_bias: clamp(value(effects, "vigBias", 0), 0, 1),
      patterns,
    },
  };
}

function temporalSource(row) {
  const stored = createTemporalProfile(row.id);
  stored.title = row.title;
  stored.created_at_epoch_ms = SOURCE_CREATED_AT_EPOCH_MS;
  stored.profile = {
    ...stored.profile,
    id: row.id,
    title: row.title,
    source_label: row.title,
    duration_seconds: 15,
    color_1: "#000000",
    color_2: row.color_2 ?? "#ffffff",
    frequency_hz: row.frequency_hz,
    duty_percent: 50,
    noise_type: "white",
    noise_resolution: 1,
    noise_phase_1: row.noise_phase_1 ?? false,
    noise_amplitude_1: row.noise_amplitude_1 ?? 0.2,
    noise_phase_2: false,
    noise_amplitude_2: 0.2,
    fixation_enabled: row.fixation_enabled ?? false,
    fixation_color: "#ff0000",
    fixation_size: 15,
  };
  return stored;
}

const ORIGINALS = Object.freeze([
  ...INTERFERENCE_SOURCES.map(interferenceSource),
  ...TEMPORAL_SOURCES.map(temporalSource),
]);

export const TREVOR_PORTAL_CARDS = Object.freeze(ORIGINALS.map(profile => Object.freeze({
  id: profile.id,
  title: profile.title,
  kind: profile.kind,
  category: profile.kind === "interference" ? "Simulation" : "Strobe",
})));

export function createTrevorOriginalProfiles() {
  return structuredClone(ORIGINALS);
}

export function getTrevorOriginalProfile(id) {
  const original = ORIGINALS.find(profile => profile.id === id);
  return original ? structuredClone(original) : null;
}

export function isTrevorOriginalId(id) {
  return ORIGINALS.some(profile => profile.id === id);
}
