package com.savenest.metadata;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class AddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.1.2.3", "0.0.0.0", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "192.0.2.1", "198.18.0.1", "224.0.0.1", "255.255.255.255",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
            "64:ff9b::a9fe:a9fe", "2002:7f00:0001::", "2001:db8::1", "ff02::1"})
    void blocksNonPublic(String ip) throws Exception {
        assertThat(AddressPolicy.STRICT.isAllowedAddress(InetAddress.getByName(ip))).as(ip).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111", "::ffff:8.8.8.8"})
    void allowsPublic(String ip) throws Exception {
        assertThat(AddressPolicy.STRICT.isAllowedAddress(InetAddress.getByName(ip))).as(ip).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "foo.localhost", "printer.local", "metadata.google.internal", "intranet",
            "router.home.arpa"})
    void blocksInternalHostnames(String host) {
        assertThat(AddressPolicy.STRICT.isAllowedHostname(host)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "www.youtube.com"})
    void allowsPublicHostnames(String host) {
        assertThat(AddressPolicy.STRICT.isAllowedHostname(host)).isTrue();
    }
}
