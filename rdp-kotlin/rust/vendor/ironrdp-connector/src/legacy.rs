use std::borrow::Cow;

use ironrdp_core::{Decode, Encode, WriteBuf, decode, encode_vec};
use ironrdp_pdu::rdp;
use ironrdp_pdu::rdp::headers::{BASIC_SECURITY_HEADER_SIZE, BasicSecurityHeaderFlags, ServerDeactivateAll};
use ironrdp_pdu::rdp::multitransport::MultitransportRequestPdu;
use ironrdp_pdu::x224::X224;

use crate::{ConnectorError, ConnectorErrorExt as _, ConnectorResult, reason_err};

pub fn encode_send_data_request<T>(
    initiator_id: u16,
    channel_id: u16,
    user_msg: &T,
    buf: &mut WriteBuf,
) -> ConnectorResult<usize>
where
    T: Encode,
{
    let user_data = encode_vec(user_msg).map_err(ConnectorError::encode)?;

    let pdu = ironrdp_pdu::mcs::SendDataRequest {
        initiator_id,
        channel_id,
        user_data: Cow::Owned(user_data),
    };

    let written = ironrdp_core::encode_buf(&X224(pdu), buf).map_err(ConnectorError::encode)?;

    Ok(written)
}

#[derive(Debug, Clone, Copy)]
pub struct SendDataIndicationCtx<'a> {
    pub initiator_id: u16,
    pub channel_id: u16,
    pub user_data: &'a [u8],
}

impl<'a> SendDataIndicationCtx<'a> {
    pub fn decode_user_data<'de, T>(&self) -> ConnectorResult<T>
    where
        T: Decode<'de>,
        'a: 'de,
    {
        let msg = decode::<T>(self.user_data).map_err(ConnectorError::decode)?;
        Ok(msg)
    }
}

pub fn decode_send_data_indication(src: &[u8]) -> ConnectorResult<SendDataIndicationCtx<'_>> {
    use ironrdp_pdu::mcs::McsMessage;

    let mcs_msg = decode::<X224<McsMessage<'_>>>(src).map_err(ConnectorError::decode)?;

    match mcs_msg.0 {
        McsMessage::SendDataIndication(msg) => {
            let Cow::Borrowed(user_data) = msg.user_data else {
                unreachable!()
            };

            Ok(SendDataIndicationCtx {
                initiator_id: msg.initiator_id,
                channel_id: msg.channel_id,
                user_data,
            })
        }
        McsMessage::DisconnectProviderUltimatum(msg) => Err(reason_err!(
            "decode_send_data_indication",
            "received disconnect provider ultimatum: {:?}",
            msg.reason
        )),
        _ => Err(reason_err!(
            "decode_send_data_indication",
            "unexpected MCS message: {}",
            ironrdp_core::name(&mcs_msg)
        )),
    }
}

pub fn encode_share_control(
    initiator_id: u16,
    channel_id: u16,
    share_id: u32,
    pdu: rdp::headers::ShareControlPdu,
    buf: &mut WriteBuf,
) -> ConnectorResult<usize> {
    let pdu_source = initiator_id;

    let share_control_header = rdp::headers::ShareControlHeader {
        share_control_pdu: pdu,
        pdu_source,
        share_id,
    };

    encode_send_data_request(initiator_id, channel_id, &share_control_header, buf)
}

#[derive(Debug, Clone)]
pub struct ShareControlCtx {
    pub initiator_id: u16,
    pub channel_id: u16,
    pub share_id: u32,
    pub pdu_source: u16,
    pub pdu: rdp::headers::ShareControlPdu,
}

pub fn decode_share_control(ctx: SendDataIndicationCtx<'_>) -> ConnectorResult<ShareControlCtx> {
    let user_msg = ctx.decode_user_data::<rdp::headers::ShareControlHeader>()?;

    Ok(ShareControlCtx {
        initiator_id: ctx.initiator_id,
        channel_id: ctx.channel_id,
        share_id: user_msg.share_id,
        pdu_source: user_msg.pdu_source,
        pdu: user_msg.share_control_pdu,
    })
}

pub fn encode_share_data(
    initiator_id: u16,
    channel_id: u16,
    share_id: u32,
    pdu: rdp::headers::ShareDataPdu,
    buf: &mut WriteBuf,
) -> ConnectorResult<usize> {
    let share_data_header = rdp::headers::ShareDataHeader {
        share_data_pdu: pdu,
        stream_priority: rdp::headers::StreamPriority::Medium,
        compression_flags: rdp::headers::CompressionFlags::empty(),
        compression_type: rdp::client_info::CompressionType::K8, // ignored if CompressionFlags::empty()
    };

    let share_control_pdu = rdp::headers::ShareControlPdu::Data(share_data_header);

    encode_share_control(initiator_id, channel_id, share_id, share_control_pdu, buf)
}

#[derive(Debug, Clone)]
pub struct ShareDataCtx {
    pub initiator_id: u16,
    pub channel_id: u16,
    pub share_id: u32,
    pub pdu_source: u16,
    pub pdu: rdp::headers::ShareDataPdu,
}

pub fn decode_share_data(ctx: SendDataIndicationCtx<'_>) -> ConnectorResult<ShareDataCtx> {
    let ctx = decode_share_control(ctx)?;

    let rdp::headers::ShareControlPdu::Data(share_data_header) = ctx.pdu else {
        return Err(reason_err!(
            "decode_share_data",
            "received unexpected Share Control PDU: got {} (expected Data PDU)",
            ctx.pdu.as_short_name(),
        ));
    };

    Ok(ShareDataCtx {
        initiator_id: ctx.initiator_id,
        channel_id: ctx.channel_id,
        share_id: ctx.share_id,
        pdu_source: ctx.pdu_source,
        pdu: share_data_header.share_data_pdu,
    })
}

/// Peek whether this Send Data Indication's user data is a Server Font Map PDU,
/// without decoding the FontPdu body.
///
/// VirtualBox's VRDP server sends the Server Font Map with an empty (0-byte)
/// FontPdu body, where `ironrdp-pdu`'s `FontPdu::decode` requires the full 8
/// bytes (numberEntries/totalNumEntries/mapFlags/entrySize) and fails with
/// `NotEnoughBytes` — killing the connect right after auth (Haven #422). The
/// Font Map's contents are ignored by the finalization sequence anyway (it's
/// only the "server may now send graphics" signal, MS-RDPBCGR 1.3.1.1), so the
/// finalization sequence uses this to accept a short/empty Font Map from
/// lenient servers instead of aborting — matching mstsc/FreeRDP behaviour.
///
/// `pduType2` sits at offset 14: a 6-byte Share Control header (totalLength u16,
/// pduType u16, pduSource u16) then 8 bytes into the Share Data header (shareId
/// u32, pad1 u8, streamId u8, uncompressedLength u16). MS-RDPBCGR 2.2.8.1.1.1.1
/// / 2.2.8.1.1.1.2; PDUTYPE_DATAPDU = 0x7 (low nibble of pduType), PDUTYPE2_FONTMAP = 0x28.
pub fn is_server_font_map(user_data: &[u8]) -> bool {
    const PDUTYPE_DATAPDU: u16 = 0x7;
    const PDUTYPE2_FONTMAP: u8 = 0x28;
    if user_data.len() < 15 {
        return false;
    }
    let pdu_type = u16::from_le_bytes([user_data[2], user_data[3]]);
    (pdu_type & 0x0F) == PDUTYPE_DATAPDU && user_data[14] == PDUTYPE2_FONTMAP
}

pub enum IoChannelPdu {
    Data(ShareDataCtx),
    DeactivateAll(ServerDeactivateAll),
    /// Server Initiate Multitransport Request PDU.
    ///
    /// Received when the server wants the client to establish a sideband UDP transport.
    MultitransportRequest(MultitransportRequestPdu),
}

pub fn decode_io_channel(ctx: SendDataIndicationCtx<'_>) -> ConnectorResult<IoChannelPdu> {
    // Multitransport PDUs use BasicSecurityHeader (flags:u16, flagsHi:u16) instead
    // of the ShareControlHeader (totalLength:u16, pduType:u16, ...) used by all
    // other IO channel PDUs. We discriminate by checking flagsHi == 0 (ShareControl
    // has pduType there, which is always non-zero) and requiring flags to be a valid
    // BasicSecurityHeaderFlags combination.
    if ctx.user_data.len() >= BASIC_SECURITY_HEADER_SIZE {
        let flags_raw = u16::from_le_bytes([ctx.user_data[0], ctx.user_data[1]]);
        let flags_hi = u16::from_le_bytes([ctx.user_data[2], ctx.user_data[3]]);

        if flags_hi == 0 {
            if let Some(flags) = BasicSecurityHeaderFlags::from_bits(flags_raw) {
                if flags.contains(BasicSecurityHeaderFlags::TRANSPORT_REQ) {
                    if let Ok(pdu) = decode::<MultitransportRequestPdu>(ctx.user_data) {
                        return Ok(IoChannelPdu::MultitransportRequest(pdu));
                    }
                }
            }
        }
    }

    let ctx = decode_share_control(ctx)?;

    match ctx.pdu {
        rdp::headers::ShareControlPdu::ServerDeactivateAll(deactivate_all) => {
            Ok(IoChannelPdu::DeactivateAll(deactivate_all))
        }
        rdp::headers::ShareControlPdu::Data(share_data_header) => {
            let share_data_ctx = ShareDataCtx {
                initiator_id: ctx.initiator_id,
                channel_id: ctx.channel_id,
                share_id: ctx.share_id,
                pdu_source: ctx.pdu_source,
                pdu: share_data_header.share_data_pdu,
            };

            Ok(IoChannelPdu::Data(share_data_ctx))
        }
        other => Err(reason_err!(
            "decode_io_channel",
            "received unexpected Share Control PDU: got {} (expected Data PDU or Server Deactivate All PDU)",
            other.as_short_name(),
        )),
    }
}

#[cfg(test)]
mod tests {
    use super::is_server_font_map;

    /// Share Control header (totalLength, pduType, pduSource) + Share Data
    /// header, with `pdu_type2` at offset 14 and no FontPdu body — the shape
    /// VirtualBox VRDP sends for the Server Font Map (#422).
    fn share_data_user_data(pdu_type: u16, pdu_type2: u8) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&18u16.to_le_bytes()); // totalLength
        v.extend_from_slice(&pdu_type.to_le_bytes()); // pduType
        v.extend_from_slice(&3u16.to_le_bytes()); // pduSource
        v.extend_from_slice(&0u32.to_le_bytes()); // shareId
        v.push(0); // pad1
        v.push(0); // streamId
        v.extend_from_slice(&0u16.to_le_bytes()); // uncompressedLength
        v.push(pdu_type2); // pduType2 @ offset 14
        v.push(0); // compressedType
        v.extend_from_slice(&0u16.to_le_bytes()); // compressedLength
        v // empty FontPdu body follows
    }

    #[test]
    fn detects_empty_server_font_map() {
        // PDUTYPE_DATAPDU (0x7) in the low nibble, PDUTYPE2_FONTMAP (0x28).
        assert!(is_server_font_map(&share_data_user_data(0x17, 0x28)));
    }

    #[test]
    fn ignores_non_font_map_data_pdu() {
        // A Data PDU that isn't a Font Map (e.g. PDUTYPE2_CONTROL 0x14).
        assert!(!is_server_font_map(&share_data_user_data(0x17, 0x14)));
    }

    #[test]
    fn ignores_non_data_share_control_pdu() {
        // pduType low nibble != DATAPDU (e.g. Confirm Active 0x3), even with the
        // FontMap byte present, must not be mistaken for a Font Map.
        assert!(!is_server_font_map(&share_data_user_data(0x13, 0x28)));
    }

    #[test]
    fn ignores_truncated_buffer() {
        assert!(!is_server_font_map(&[0u8; 10]));
        assert!(!is_server_font_map(&[]));
    }
}
