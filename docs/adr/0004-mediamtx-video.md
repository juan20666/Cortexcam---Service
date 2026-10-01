# ADR-0004: MediaMTX para video en vivo

**Estado:** Aceptado  
**Fecha:** 2026-09-30  

## Contexto

El sistema actual captura la ventana de la app Yoosee con Win32 y escribe frame.jpg a disco cada iteración. Esto es frágil, sólo funciona en Windows, y tiene race conditions.

## Decisión

Usar MediaMTX como proxy de video: la cámara IP envía RTSP a MediaMTX (una sola conexión), y MediaMTX reexpone el stream como WebRTC (WHEP) y HLS para el frontend. La ingesta también lee desde MediaMTX.

## Consecuencias

- La cámara sólo recibe una conexión RTSP.
- Video en vivo sin polling de imágenes (latencia sub-segundo con WebRTC).
- Desaparece la dependencia de Win32 y la app Yoosee.
- Requiere que la cámara soporte RTSP (verificar modelo y activarlo).
