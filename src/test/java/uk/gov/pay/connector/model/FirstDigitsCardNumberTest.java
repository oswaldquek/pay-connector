package uk.gov.pay.connector.model;

import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

public class FirstDigitsCardNumberTest {


    @Test
    public void shouldConvertValidFirstSixDigitsOfCard() {
        Assert.assertThat(FirstDigitsCardNumber.of("123456").toString(), is("123456"));
        Assert.assertThat(FirstDigitsCardNumber.ofNullable("123456").toString(), is("123456"));
    }

    @Test
    public void shouldReturnNullIfStringIsNull() {
        Assert.assertThat(FirstDigitsCardNumber.ofNullable(null), is(nullValue()));
    }

    @Test(expected = RuntimeException.class)
    public void shouldThrowIfNonNumericFirstSixDigitsOfCard() {
        FirstDigitsCardNumber.of("a23442");
    }

    @Test(expected = RuntimeException.class)
    public void shouldThrowIfNotSixDigitsOfCard() {
        FirstDigitsCardNumber.of("22345");
    }

    @Test(expected = RuntimeException.class)
    public void shouldThrowIfNullSixDigitsOfCard() {
        FirstDigitsCardNumber.of(null);
    }

}